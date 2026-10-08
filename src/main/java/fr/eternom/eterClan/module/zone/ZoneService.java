package fr.eternom.eterClan.module.zone;

import fr.eternom.eterClan.module.claim.ClaimIndex.ChunkKey;
import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanPermission;
import fr.eternom.eterClan.module.clan.ClanRepository;
import fr.eternom.eterClan.module.clan.ClanService;
import fr.eternom.eterClan.module.sync.ClanSync;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Les parcelles d'un clan. Création, suppression, loyer : permission ZONES (gestion seulement, jamais l'accès au
 * contenu). Une parcelle se trace avec deux coins (pos1, pos2 : le bloc visé), entièrement dans le terrain du clan sur
 * ce serveur, sans chevaucher une autre. Un membre la loue (premier loyer payé tout de suite, puis chaque semaine par
 * WeeklyCycle) ; il y décide seul de qui construit et ouvre (trust). Une parcelle louée ne peut pas être supprimée :
 * la location doit d'abord finir (le locataire part, ou ZONES y met fin).
 */
public class ZoneService {

    private static final Pattern NAME = Pattern.compile("[\\p{L}0-9_-]{2,24}");
    private static final Duration WEEK = Duration.ofDays(7);
    private static final int REACH = 6;

    private final JavaPlugin plugin;
    private final ZoneRepository zones;
    private final ClanRepository clans;
    private final ClanSync sync;
    private final ClanService clanService;
    private final Messages messages;
    private final int maxArea;
    /** Coins choisis par chaque joueur : [0] = pos1, [1] = pos2. */
    private final Map<UUID, Location[]> selections = new ConcurrentHashMap<>();

    public ZoneService(JavaPlugin plugin, ZoneRepository zones, ClanRepository clans, ClanSync sync, ClanService clanService,
                       Messages messages) {
        this.plugin = plugin;
        this.zones = zones;
        this.clans = clans;
        this.sync = sync;
        this.clanService = clanService;
        this.messages = messages;
        this.maxArea = Math.max(1, plugin.getConfig().getInt("zones.max-area", 4096));
    }

    /** corner : 0 ou 1. Le bloc visé (à REACH blocs), sinon celui sous les pieds. */
    public void select(Player player, int corner) {
        Block target = player.getTargetBlockExact(REACH);
        Location location = (target != null ? target.getLocation() : player.getLocation().getBlock().getLocation());
        selections.computeIfAbsent(player.getUniqueId(), key -> new Location[2])[corner] = location;
        messages.send(player, corner == 0 ? "zone.pos1" : "zone.pos2",
                "x", String.valueOf(location.getBlockX()), "z", String.valueOf(location.getBlockZ()));
    }

    public void create(Player player, String name) {
        clanService.clanWith(player, ClanPermission.ZONES).ifPresent(clan -> {
            Location[] corners = selections.get(player.getUniqueId());
            if (!NAME.matcher(name).matches()) {
                messages.send(player, "zone.name-invalid");
                return;
            }
            if (corners == null || corners[0] == null || corners[1] == null || corners[0].getWorld() != corners[1].getWorld()) {
                messages.send(player, "zone.no-selection");
                return;
            }
            String world = corners[0].getWorld().getName();
            int minX = Math.min(corners[0].getBlockX(), corners[1].getBlockX());
            int maxX = Math.max(corners[0].getBlockX(), corners[1].getBlockX());
            int minZ = Math.min(corners[0].getBlockZ(), corners[1].getBlockZ());
            int maxZ = Math.max(corners[0].getBlockZ(), corners[1].getBlockZ());
            if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > maxArea) {
                messages.send(player, "zone.too-big", "max", String.valueOf(maxArea));
                return;
            }
            if (!insideClanLand(clan, world, minX, minZ, maxX, maxZ)) {
                messages.send(player, "zone.outside-land");
                return;
            }
            if (sync.zones().overlaps(world, minX, minZ, maxX, maxZ)) {
                messages.send(player, "zone.overlaps");
                return;
            }
            if (sync.zones().byName(clan.id(), name).isPresent()) {
                messages.send(player, "zone.name-taken", "zone", name);
                return;
            }
            write(player, () -> {
                zones.create(clan.id(), sync.server(), world, minX, minZ, maxX, maxZ, name);
                return "zone.created";
            }, "zone", name, "area", String.valueOf((maxX - minX + 1) * (maxZ - minZ + 1)));
            selections.remove(player.getUniqueId());
        });
    }

    public void delete(Player player, String name) {
        managed(player, name).ifPresent(zone -> {
            if (zone.isRented()) {
                messages.send(player, "zone.rented-cannot-delete", "zone", zone.name());
                return;
            }
            write(player, () -> zones.delete(zone.id()) ? "zone.deleted" : "zone.rented-cannot-delete", "zone", zone.name());
        });
    }

    /** Loyer par semaine (0 = plus à louer) ; pas pendant une location (le prix convenu ne change pas). */
    public void setRent(Player player, String name, double rent) {
        managed(player, name).ifPresent(zone -> {
            if (zone.isRented()) {
                messages.send(player, "zone.rented-locked", "zone", zone.name());
                return;
            }
            double price = Double.isFinite(rent) ? Math.max(0, Math.floor(rent * 100) / 100) : 0;
            write(player, () -> {
                zones.setRent(zone.id(), price);
                return price > 0 ? "zone.rent-set" : "zone.rent-off";
            }, "zone", zone.name(), "rent", Money.format(price));
        });
    }

    /** Louer une parcelle de son clan : le premier loyer est payé tout de suite (porte-monnaie -> réserve). */
    public void take(Player player, String name) {
        clanService.clanOf(player).ifPresent(clan -> {
            Optional<Zone> found = sync.zones().byName(clan.id(), name);
            if (found.isEmpty()) {
                messages.send(player, "zone.unknown", "zone", name);
                return;
            }
            Zone zone = found.get();
            if (zone.isRented() || zone.rent() <= 0) {
                messages.send(player, "zone.not-for-rent", "zone", zone.name());
                return;
            }
            Economy economy = Money.economy();
            if (economy == null) {
                messages.send(player, "economy.unavailable");
                return;
            }
            write(player, () -> {
                if (!economy.withdrawPlayer(player, zone.rent()).transactionSuccess()) {
                    return "zone.rent-not-enough";
                }
                if (!zones.take(zone.id(), player.getUniqueId(), player.getName(), System.currentTimeMillis() + WEEK.toMillis())) {
                    economy.depositPlayer(player, zone.rent()); // louée entre-temps : remboursé
                    return "zone.not-for-rent";
                }
                clans.addReserve(clan.id(), zone.rent());
                sync.clanChanged(clan.id());
                return "zone.rented";
            }, "zone", zone.name(), "rent", Money.format(zone.rent()));
        });
    }

    /** Fin de location : par le locataire, ou par un membre qui a ZONES. */
    public void end(Player player, String name) {
        clanService.clanOf(player).ifPresent(clan -> {
            Optional<Zone> found = sync.zones().byName(clan.id(), name).filter(Zone::isRented);
            if (found.isEmpty()) {
                messages.send(player, "zone.not-rented", "zone", name);
                return;
            }
            Zone zone = found.get();
            boolean tenant = player.getUniqueId().equals(zone.tenant());
            if (!tenant && !clan.can(player.getUniqueId(), ClanPermission.ZONES)) {
                messages.send(player, "clan.no-permission");
                return;
            }
            write(player, () -> {
                zones.release(zone.id());
                return "zone.ended";
            }, "zone", zone.name());
            if (!tenant) {
                sync.bus().notify(zone.tenant(), "zone.ended-by-clan", true, "zone", zone.name());
            }
        });
    }

    /** Le locataire autorise (ou retire) un joueur dans sa parcelle. */
    public void trust(Player player, String name, String targetName, boolean allow) {
        clanService.clanOf(player).ifPresent(clan -> {
            Optional<Zone> found = sync.zones().byName(clan.id(), name)
                    .filter(zone -> player.getUniqueId().equals(zone.tenant()));
            if (found.isEmpty()) {
                messages.send(player, "zone.not-tenant", "zone", name);
                return;
            }
            Zone zone = found.get();
            clanService.findPlayer(player, targetName, target -> {
                Set<UUID> trusted = new HashSet<>(zone.trusted());
                if (allow) {
                    trusted.add(target.uuid());
                } else {
                    trusted.remove(target.uuid());
                }
                write(player, () -> {
                    zones.setTrusted(zone.id(), trusted);
                    return allow ? "zone.trusted" : "zone.untrusted";
                }, "zone", zone.name(), "player", target.name());
            });
        });
    }

    public void list(Player player) {
        clanService.clanOf(player).ifPresent(clan -> {
            var list = sync.zones().ofClan(clan.id());
            messages.send(player, list.isEmpty() ? "zone.list-empty" : "zone.list-header", "count", String.valueOf(list.size()));
            for (Zone zone : list) {
                String key = zone.isRented() ? "zone.list-rented" : zone.rent() > 0 ? "zone.list-for-rent" : "zone.list-free";
                player.sendMessage(messages.get(player, key, "zone", zone.name(), "area", String.valueOf(zone.area()),
                        "rent", Money.format(zone.rent()), "tenant", zone.tenantName() == null ? "" : zone.tenantName()));
            }
        });
    }

    /** Parcelle de son clan, si le joueur a ZONES. */
    private Optional<Zone> managed(Player player, String name) {
        return clanService.clanWith(player, ClanPermission.ZONES).flatMap(clan -> {
            Optional<Zone> zone = sync.zones().byName(clan.id(), name);
            if (zone.isEmpty()) {
                messages.send(player, "zone.unknown", "zone", name);
            }
            return zone;
        });
    }

    /** Tous les chunks du rectangle sont au clan, sur ce serveur. */
    private boolean insideClanLand(Clan clan, String world, int minX, int minZ, int maxX, int maxZ) {
        for (int x = minX >> 4; x <= maxX >> 4; x++) {
            for (int z = minZ >> 4; z <= maxZ >> 4; z++) {
                if (sync.claims().owner(new ChunkKey(world, x, z)).orElse(-1) != clan.id()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Écrit en tâche de fond (la clé de message renvoyée), recharge les parcelles, puis prévient le joueur. */
    private void write(Player player, Supplier<String> work, String... placeholders) {
        Tasks.async(plugin, player, () -> {
            String key = work.get();
            sync.zonesChanged();
            return key;
        }, key -> messages.send(player, key, placeholders), () -> messages.send(player, "error.generic"));
    }
}
