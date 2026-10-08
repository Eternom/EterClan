package fr.eternom.eterClan.module.claim;

import fr.eternom.eterClan.module.claim.ClaimIndex.ChunkKey;
import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanPermission;
import fr.eternom.eterClan.module.clan.ClanRepository;
import fr.eternom.eterClan.module.clan.ClanService;
import fr.eternom.eterClan.module.sync.ClanSync;
import fr.eternom.eterClan.module.zone.Zone;
import fr.eternom.eterClan.module.zone.ZoneRepository;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.OptionalLong;

/**
 * Le terrain d'un clan : UN seul territoire d'un tenant (sa base). Le premier chunk se pose n'importe où, les suivants
 * contre un chunk du clan ; payés par la réserve au prix de Pricing (comptés sur tout le réseau). Rendre un chunk : sans
 * remboursement, pas sous une parcelle louée, et jamais s'il coupe le territoire en deux (le dernier, oui : le clan peut
 * s'installer ailleurs). Voir les bords : particules. Mondes autorisés : config.yml > land.worlds (vide = tous).
 */
public class ClaimService {

    private static final int BORDER_RADIUS = 3;
    private static final int BORDER_SECONDS = 10;

    private final JavaPlugin plugin;
    private final ClaimRepository claims;
    private final ZoneRepository zones;
    private final ClanRepository clans;
    private final ClanSync sync;
    private final ClanService clanService;
    private final Messages messages;
    private final Pricing pricing;
    private final List<String> worlds;
    /** Terrain possible sur ce serveur (land.enabled : la survie, pas les lobbys ni les mondes ressources). */
    private final boolean enabled;

    public ClaimService(JavaPlugin plugin, ClaimRepository claims, ZoneRepository zones, ClanRepository clans, ClanSync sync,
                        ClanService clanService, Messages messages, Pricing pricing) {
        this.plugin = plugin;
        this.claims = claims;
        this.zones = zones;
        this.clans = clans;
        this.sync = sync;
        this.clanService = clanService;
        this.messages = messages;
        this.pricing = pricing;
        this.worlds = plugin.getConfig().getStringList("land.worlds");
        this.enabled = plugin.getConfig().getBoolean("land.enabled", false);
    }

    public void claim(Player player) {
        clanService.clanWith(player, ClanPermission.CLAIM).ifPresent(clan -> {
            ChunkKey chunk = ChunkKey.of(player.getLocation());
            if (!enabled) {
                messages.send(player, "claim.disabled-here");
                return;
            }
            if (!worlds.isEmpty() && !worlds.contains(chunk.world())) {
                messages.send(player, "claim.world-forbidden");
                return;
            }
            OptionalLong owner = sync.claims().owner(chunk);
            if (owner.isPresent()) {
                messages.send(player, owner.getAsLong() == clan.id() ? "claim.already-yours" : "claim.taken");
                return;
            }
            Tasks.async(plugin, player, () -> {
                // Lu en base au moment de payer : un autre serveur a pu poser un chunk entre-temps
                Clan fresh = clans.byId(clan.id()).orElse(clan);
                // Un seul territoire : le premier chunk n'importe où, les suivants contre un chunk du clan
                if (fresh.chunks() > 0 && !sync.claims().touches(chunk, clan.id())) {
                    return new Result(sync.claims().hasLand(clan.id()) ? "claim.not-adjacent" : "claim.land-elsewhere", 0);
                }
                double price = pricing.priceOf(fresh.chunks() + 1);
                if (price > 0 && !clans.takeReserve(clan.id(), price)) {
                    return new Result("claim.reserve-not-enough", price);
                }
                if (!claims.claim(sync.server(), chunk.world(), chunk.x(), chunk.z(), clan.id(), System.currentTimeMillis())) {
                    clans.addReserve(clan.id(), price); // pris entre-temps : remboursé
                    return new Result("claim.taken", 0);
                }
                sync.claimsChanged();
                sync.clanChanged(clan.id());
                return new Result("claim.claimed", price);
            }, result -> {
                messages.send(player, result.key(), "price", Money.format(result.price()),
                        "upkeep", Money.format(pricing.upkeepOf(clan.chunks() + 1)));
                if (result.key().equals("claim.claimed")) {
                    player.playSound(player, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.2f);
                    showBorders(player);
                }
            }, () -> messages.send(player, "error.generic"));
        });
    }

    public void unclaim(Player player) {
        clanService.clanWith(player, ClanPermission.CLAIM).ifPresent(clan -> {
            ChunkKey chunk = ChunkKey.of(player.getLocation());
            if (sync.claims().owner(chunk).orElse(-1) != clan.id()) {
                messages.send(player, "claim.not-yours");
                return;
            }
            List<Zone> onChunk = sync.zones().ofClan(clan.id()).stream()
                    .filter(zone -> zone.overlapsChunk(chunk.world(), chunk.x(), chunk.z())).toList();
            if (onChunk.stream().anyMatch(Zone::isRented)) {
                messages.send(player, "claim.rented-zone");
                return;
            }
            if (!sync.claims().staysConnected(clan.id(), chunk)) {
                messages.send(player, "claim.would-split");
                return;
            }
            Tasks.async(plugin, player, () -> {
                boolean removed = claims.unclaim(sync.server(), chunk.world(), chunk.x(), chunk.z(), clan.id());
                if (removed) {
                    onChunk.forEach(zone -> zones.delete(zone.id()));
                    sync.claimsChanged();
                    sync.zonesChanged();
                    sync.clanChanged(clan.id());
                }
                return removed;
            }, removed -> messages.send(player, removed ? "claim.unclaimed" : "claim.not-yours"),
                    () -> messages.send(player, "error.generic"));
        });
    }

    /** Ce qu'il y a ici, et le prix du prochain chunk du clan du joueur. */
    public void here(Player player) {
        OptionalLong owner = sync.claims().owner(player.getLocation());
        String name = owner.isEmpty() ? null : sync.cache().get(owner.getAsLong()).map(Clan::name).orElse("?");
        messages.send(player, name == null ? "claim.here-wild" : "claim.here", "clan", name == null ? "" : name);
        sync.cache().of(player.getUniqueId()).ifPresent(clan -> messages.send(player, "claim.next-price",
                "chunks", String.valueOf(clan.chunks()), "free", String.valueOf(pricing.free()),
                "price", Money.format(pricing.priceOf(clan.chunks() + 1)),
                "upkeep", Money.format(pricing.upkeepOf(clan.chunks()))));
        showBorders(player);
    }

    /**
     * Bords des terrains autour du joueur pendant 10 s : une ligne de particules là où deux chunks voisins n'ont pas le
     * même propriétaire (vert : son clan ; rouge : un autre).
     */
    public void showBorders(Player player) {
        long own = sync.cache().of(player.getUniqueId()).map(Clan::id).orElse(-1L);
        BukkitTask[] task = new BukkitTask[1];
        int[] runs = {0};
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || ++runs[0] > BORDER_SECONDS * 2) {
                task[0].cancel();
                return;
            }
            ChunkKey center = ChunkKey.of(player.getLocation());
            double y = player.getLocation().getY() + 1;
            for (int dx = -BORDER_RADIUS; dx <= BORDER_RADIUS; dx++) {
                for (int dz = -BORDER_RADIUS; dz <= BORDER_RADIUS; dz++) {
                    ChunkKey chunk = center.relative(dx, dz);
                    OptionalLong owner = sync.claims().owner(chunk);
                    if (owner.isEmpty()) {
                        continue;
                    }
                    Color color = owner.getAsLong() == own ? Color.LIME : Color.RED;
                    edges(player, chunk, owner.getAsLong(), y, color);
                }
            }
        }, 0, 10);
    }

    /** Les côtés du chunk qui touchent un autre propriétaire. */
    private void edges(Player player, ChunkKey chunk, long owner, double y, Color color) {
        World world = player.getWorld();
        int x0 = chunk.x() << 4;
        int z0 = chunk.z() << 4;
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.2f);
        for (int i = 0; i <= 16; i += 2) {
            if (sync.claims().owner(chunk.relative(0, -1)).orElse(-1) != owner) {
                player.spawnParticle(Particle.DUST, new Location(world, x0 + i, y, z0), 1, dust);
            }
            if (sync.claims().owner(chunk.relative(0, 1)).orElse(-1) != owner) {
                player.spawnParticle(Particle.DUST, new Location(world, x0 + i, y, z0 + 16), 1, dust);
            }
            if (sync.claims().owner(chunk.relative(-1, 0)).orElse(-1) != owner) {
                player.spawnParticle(Particle.DUST, new Location(world, x0, y, z0 + i), 1, dust);
            }
            if (sync.claims().owner(chunk.relative(1, 0)).orElse(-1) != owner) {
                player.spawnParticle(Particle.DUST, new Location(world, x0 + 16, y, z0 + i), 1, dust);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Pricing pricing() {
        return pricing;
    }

    private record Result(String key, double price) {
    }
}
