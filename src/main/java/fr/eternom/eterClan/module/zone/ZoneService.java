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
import fr.eternom.eterEconomy.api.EconomyApi;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Les parcelles d'un clan (appelé par les menus). Création, suppression, loyer : permission ZONES (gestion seulement,
 * jamais l'accès au contenu). Une parcelle se trace avec deux coins cliqués dans le monde (ZoneSelection), entièrement
 * dans le terrain du clan sur ce serveur, sans chevaucher une autre. Un membre la loue (premier loyer payé tout de
 * suite, puis chaque semaine par WeeklyCycle) ; il y décide seul de qui construit et ouvre. Une parcelle louée ne peut
 * pas être supprimée : la location doit d'abord finir (le locataire part, ou ZONES y met fin).
 * after : rouvre le menu une fois l'action faite.
 */
public class ZoneService {

    private static final Pattern NAME = Pattern.compile("[\\p{L}0-9_-]{2,24}");
    private static final Duration WEEK = Duration.ofDays(7);

    private final JavaPlugin plugin;
    private final ZoneRepository zones;
    private final ClanRepository clans;
    private final ClanSync sync;
    private final ClanService clanService;
    private final Messages messages;
    private final int maxArea;

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

    /** Vérifie un rectangle (avant de demander le nom) : message et false s'il ne convient pas. */
    public boolean checkArea(Player player, Location first, Location second) {
        Clan clan = clanService.clanWith(player, ClanPermission.ZONES).orElse(null);
        if (clan == null) {
            return false;
        }
        if (first.getWorld() != second.getWorld()) {
            messages.send(player, "zone.other-world");
            return false;
        }
        Box box = Box.of(first, second);
        if ((long) box.width() * box.depth() > maxArea) {
            messages.send(player, "zone.too-big", "max", String.valueOf(maxArea));
            return false;
        }
        if (!insideClanLand(clan, box)) {
            messages.send(player, "zone.outside-land");
            return false;
        }
        if (sync.zones().overlaps(box.world(), box.minX(), box.minZ(), box.maxX(), box.maxZ())) {
            messages.send(player, "zone.overlaps");
            return false;
        }
        return true;
    }

    public void create(Player player, String name, Location first, Location second, Runnable after) {
        clanService.clanWith(player, ClanPermission.ZONES).ifPresent(clan -> {
            if (!NAME.matcher(name).matches()) {
                messages.send(player, "zone.name-invalid");
                return;
            }
            if (sync.zones().byName(clan.id(), name).isPresent()) {
                messages.send(player, "zone.name-taken", "zone", name);
                return;
            }
            if (!checkArea(player, first, second)) {
                return;
            }
            Box box = Box.of(first, second);
            write(player, after, () -> {
                zones.create(clan.id(), sync.server(), box.world(), box.minX(), box.minZ(), box.maxX(), box.maxZ(), name);
                return "zone.created";
            }, "zone", name, "area", String.valueOf(box.width() * box.depth()));
        });
    }

    public void delete(Player player, Zone zone, Runnable after) {
        if (!manages(player, zone)) {
            return;
        }
        if (zone.isRented()) {
            messages.send(player, "zone.rented-cannot-delete", "zone", zone.name());
            return;
        }
        write(player, after, () -> zones.delete(zone.id()) ? "zone.deleted" : "zone.rented-cannot-delete", "zone", zone.name());
    }

    /** Loyer par semaine (0 = plus à louer) ; pas pendant une location (le prix convenu ne change pas). */
    public void setRent(Player player, Zone zone, double rent, Runnable after) {
        if (!manages(player, zone)) {
            return;
        }
        if (zone.isRented()) {
            messages.send(player, "zone.rented-locked", "zone", zone.name());
            return;
        }
        if (!(rent >= 0)) {
            messages.send(player, "bank.amount-invalid");
            return;
        }
        double price = Math.floor(rent * 100) / 100;
        write(player, after, () -> {
            zones.setRent(zone.id(), price);
            return price > 0 ? "zone.rent-set" : "zone.rent-off";
        }, "zone", zone.name(), "rent", Money.format(price));
    }

    /** Louer une parcelle de son clan : le premier loyer est payé tout de suite (porte-monnaie -> réserve). */
    public void take(Player player, Zone zone, Runnable after) {
        Clan clan = clanService.clanOf(player).filter(found -> found.id() == zone.clanId()).orElse(null);
        if (clan == null) {
            return;
        }
        if (zone.isRented() || zone.rent() <= 0) {
            messages.send(player, "zone.not-for-rent", "zone", zone.name());
            return;
        }
        EconomyApi economy = EconomyApi.get().orElse(null);
        if (economy == null) {
            messages.send(player, "economy.unavailable");
            return;
        }
        write(player, after, () -> {
            if (!economy.withdraw(player.getUniqueId(), zone.rent(), "EterClan · loyer")) {
                return "zone.rent-not-enough";
            }
            if (!zones.take(zone.id(), player.getUniqueId(), player.getName(), System.currentTimeMillis() + WEEK.toMillis())) {
                economy.deposit(player.getUniqueId(), zone.rent(), "EterClan · loyer"); // louée entre-temps : remboursé
                return "zone.not-for-rent";
            }
            clans.addReserve(clan.id(), zone.rent());
            sync.clanChanged(clan.id());
            return "zone.rented";
        }, "zone", zone.name(), "rent", Money.format(zone.rent()));
    }

    /** Fin de location : par le locataire, ou par un membre qui a ZONES. */
    public void end(Player player, Zone zone, Runnable after) {
        Clan clan = clanService.clanOf(player).filter(found -> found.id() == zone.clanId()).orElse(null);
        if (clan == null || !zone.isRented()) {
            return;
        }
        boolean tenant = player.getUniqueId().equals(zone.tenant());
        if (!tenant && !clan.can(player.getUniqueId(), ClanPermission.ZONES)) {
            messages.send(player, "clan.no-permission");
            return;
        }
        write(player, after, () -> {
            zones.release(zone.id());
            return "zone.ended";
        }, "zone", zone.name());
        if (!tenant) {
            sync.bus().notify(zone.tenant(), "zone.ended-by-clan", true, "zone", zone.name());
        }
    }

    /** Le locataire autorise un joueur (par son pseudo) dans sa parcelle. */
    public void trust(Player player, Zone zone, String targetName, Runnable after) {
        if (!player.getUniqueId().equals(zone.tenant())) {
            messages.send(player, "zone.not-tenant", "zone", zone.name());
            return;
        }
        clanService.findPlayer(player, targetName, target -> setAccess(player, zone, target.uuid(), target.name(), true, after));
    }

    /** Le locataire retire l'accès d'un joueur. */
    public void untrust(Player player, Zone zone, UUID target, String targetName, Runnable after) {
        if (player.getUniqueId().equals(zone.tenant())) {
            setAccess(player, zone, target, targetName, false, after);
        }
    }

    public int maxArea() {
        return maxArea;
    }

    private void setAccess(Player player, Zone zone, UUID target, String targetName, boolean allow, Runnable after) {
        Set<UUID> trusted = new HashSet<>(zone.trusted());
        if (allow) {
            trusted.add(target);
        } else {
            trusted.remove(target);
        }
        write(player, after, () -> {
            zones.setTrusted(zone.id(), trusted);
            return allow ? "zone.trusted" : "zone.untrusted";
        }, "zone", zone.name(), "player", targetName);
    }

    /** Parcelle de son clan, et la permission ZONES ; message sinon. */
    private boolean manages(Player player, Zone zone) {
        return clanService.clanWith(player, ClanPermission.ZONES).filter(clan -> clan.id() == zone.clanId()).isPresent();
    }

    /** Tous les chunks du rectangle sont au clan, sur ce serveur. */
    private boolean insideClanLand(Clan clan, Box box) {
        for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
            for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
                if (sync.claims().owner(new ChunkKey(box.world(), x, z)).orElse(-1) != clan.id()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Écrit en tâche de fond (la clé de message renvoyée), recharge les parcelles, prévient le joueur, puis after. */
    private void write(Player player, Runnable after, Supplier<String> work, String... placeholders) {
        Tasks.async(plugin, player, () -> {
            String key = work.get();
            sync.zonesChanged();
            return key;
        }, key -> {
            messages.send(player, key, placeholders);
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    /** Rectangle entre deux coins, sur toute la hauteur. */
    public record Box(String world, int minX, int minZ, int maxX, int maxZ) {

        public static Box of(Location first, Location second) {
            return new Box(first.getWorld().getName(), Math.min(first.getBlockX(), second.getBlockX()),
                    Math.min(first.getBlockZ(), second.getBlockZ()), Math.max(first.getBlockX(), second.getBlockX()),
                    Math.max(first.getBlockZ(), second.getBlockZ()));
        }

        public int width() {
            return maxX - minX + 1;
        }

        public int depth() {
            return maxZ - minZ + 1;
        }
    }
}
