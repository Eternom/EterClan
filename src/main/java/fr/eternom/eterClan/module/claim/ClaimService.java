package fr.eternom.eterClan.module.claim;

import fr.eternom.eterClan.module.claim.ClaimIndex.ChunkKey;
import fr.eternom.eterClan.module.claim.LandPlan.Decision;
import fr.eternom.eterClan.module.claim.LandPlan.Kind;
import fr.eternom.eterClan.module.claim.LandPlan.Plot;
import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanPermission;
import fr.eternom.eterClan.module.clan.ClanRepository;
import fr.eternom.eterClan.module.clan.ClanService;
import fr.eternom.eterClan.module.clan.Journal;
import fr.eternom.eterClan.module.clan.Land;
import fr.eternom.eterClan.module.ranking.ClanLeague;
import fr.eternom.eterClan.module.ranking.LeagueStore;
import fr.eternom.eterClan.module.sync.ClanSync;
import fr.eternom.eterClan.module.zone.Zone;
import fr.eternom.eterClan.module.zone.ZoneRepository;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.teleport.Destination;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Le terrain d'un clan (LandPlan pour la forme, Pricing pour les prix, LandRules pour les règles) :
 * - le premier chunk devient le chunk primaire, et tout le cœur (3 × 3 autour) est pris d'un coup, gratuitement ;
 * - ensuite : un chunk du cœur reste gratuit ; une extension (contre la base) et un avant-poste (ailleurs) se paient
 *   tout de suite, par la réserve ; plusieurs chunks d'un coup depuis la carte ;
 * - rendre un chunk rembourse une part de ce qu'il a coûté (land.refund) ; le cœur ne se rend qu'avec tout le terrain ;
 * - pas de chunk dans une zone interdite, ni à moins de land.buffer chunks d'un autre clan ;
 * - le cœur se déplace (payant, une fois par période) ; le home du clan est n'importe où sur son terrain.
 * Mondes autorisés : config.yml > land.worlds (vide = tous).
 */
public class ClaimService {

    private static final int BORDER_RADIUS = 3;
    private static final int BORDER_SECONDS = 10;
    private static final long DAY_MILLIS = 86_400_000L;

    /** Ce que coûterait la prise d'un chunk, ou pourquoi c'est impossible. */
    public record Quote(Kind kind, double price, String refusal) {

        public boolean allowed() {
            return refusal == null;
        }
    }

    /** Le détail de l'entretien d'une semaine. */
    public record Upkeep(int core, int extensions, int outposts, double total, int discount) {
    }

    private final JavaPlugin plugin;
    private final ClaimRepository claims;
    private final ZoneRepository zones;
    private final ClanRepository clans;
    private final ClanSync sync;
    private final ClanService clanService;
    private final Messages messages;
    private final Pricing pricing;
    private final LandRules rules;
    private final Journal journal;
    /** Chunks pris et rendus : comptent au classement du mois (expansion). */
    private final ClanLeague league;
    private final List<String> worlds;
    /** Terrain possible sur ce serveur (land.enabled : la survie, pas les lobbys ni les mondes ressources). */
    private final boolean enabled;

    public ClaimService(JavaPlugin plugin, ClaimRepository claims, ZoneRepository zones, ClanRepository clans, ClanSync sync,
                        ClanService clanService, Messages messages, Pricing pricing, LandRules rules, Journal journal, ClanLeague league) {
        this.league = league;
        this.plugin = plugin;
        this.claims = claims;
        this.zones = zones;
        this.clans = clans;
        this.sync = sync;
        this.clanService = clanService;
        this.messages = messages;
        this.pricing = pricing;
        this.rules = rules;
        this.journal = journal;
        this.worlds = plugin.getConfig().getStringList("land.worlds");
        this.enabled = plugin.getConfig().getBoolean("land.enabled", false);
    }

    // ---------- Calculs ----------

    public Plot plot(ChunkKey chunk) {
        return new Plot(sync.server(), chunk.world(), chunk.x(), chunk.z());
    }

    public int rank(Clan clan) {
        return league.of(clan.id()).map(named -> named.standing().place()).orElse(0);
    }

    public int level(Clan clan) {
        return rules.level(clan.members().size(), clan.chunks(), rank(clan));
    }

    public int maxOutposts(Clan clan) {
        return rules.maxOutposts(level(clan));
    }

    public Upkeep upkeep(Clan clan) {
        Land land = clan.land();
        int[] paid = LandPlan.paidCounts(land.heart(), land.plots());
        int rank = rank(clan);
        int discount = rank >= 1 && rank <= pricing.leagueDiscount().size() ? pricing.leagueDiscount().get(rank - 1) : 0;
        return new Upkeep(clan.chunks() - paid[0] - paid[1], paid[0], paid[1], pricing.upkeepOf(paid[0], paid[1], rank), discount);
    }

    /** Ce que coûterait ce chunk (de ce serveur) pour ce clan, compte tenu de owned (les chunks déjà choisis compris). */
    public Quote quote(Clan clan, Plot plot, Set<Plot> owned, Plot heart) {
        if (!enabled) {
            return new Quote(null, 0, "claim.disabled-here");
        }
        if (!worlds.isEmpty() && !worlds.contains(plot.world())) {
            return new Quote(null, 0, "claim.world-forbidden");
        }
        if (rules.forbids(plot.world(), plot.x(), plot.z())) {
            return new Quote(null, 0, "claim.forbidden-area");
        }
        ChunkKey key = new ChunkKey(plot.world(), plot.x(), plot.z());
        OptionalLong owner = sync.claims().owner(key);
        if (owner.isPresent()) {
            return new Quote(null, 0, owner.getAsLong() == clan.id() ? "claim.already-yours" : "claim.taken");
        }
        if (nearOtherClan(key, clan.id())) {
            return new Quote(null, 0, "claim.too-close");
        }
        Decision decision = LandPlan.claim(heart, owned, plot, maxOutposts(clan), rules.outpostSize());
        if (!decision.allowed()) {
            return new Quote(null, 0, decision.refusal());
        }
        int[] paid = LandPlan.paidCounts(heart, owned);
        double price = switch (decision.kind()) {
            case CORE -> 0;
            case EXTENSION -> pricing.extensionPrice(paid[0] + 1);
            case OUTPOST -> pricing.outpostPrice(paid[1]);
        };
        return new Quote(decision.kind(), price, null);
    }

    /** Un chunk d'un autre clan à moins de land.buffer chunks (même monde, ce serveur). */
    private boolean nearOtherClan(ChunkKey chunk, long clanId) {
        int buffer = rules.buffer();
        for (int dx = -buffer; dx <= buffer; dx++) {
            for (int dz = -buffer; dz <= buffer; dz++) {
                OptionalLong owner = sync.claims().owner(chunk.relative(dx, dz));
                if (owner.isPresent() && owner.getAsLong() != clanId) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------- Prendre ----------

    /** Le chunk où se tient le joueur. */
    public void claim(Player player) {
        claim(player, List.of(plot(ChunkKey.of(player.getLocation()))), () -> { });
    }

    /**
     * Prendre ces chunks (la carte : plusieurs d'un coup), dans l'ordre. Le premier chunk d'un clan sans terrain devient
     * son cœur, et les 8 chunks autour sont pris avec lui (s'ils sont libres). Chaque chunk est revérifié et payé un par
     * un en base : on s'arrête au premier refus (prix affiché pour ceux déjà pris).
     */
    public void claim(Player player, List<Plot> wanted, Runnable after) {
        clanService.clanWith(player, ClanPermission.CLAIM).ifPresent(clan -> {
            if (!enabled) {
                messages.send(player, "claim.disabled-here");
                return;
            }
            String actor = player.getName();
            Tasks.async(plugin, player, () -> {
                // Lu en base au moment de payer : un autre serveur a pu poser un chunk entre-temps
                Clan fresh = clans.byId(clan.id()).orElse(clan);
                Set<Plot> owned = new HashSet<>(fresh.land().plots());
                Plot heart = fresh.land().heart();
                List<Plot> queue = new ArrayList<>(wanted);
                if (owned.isEmpty() && !queue.isEmpty()) {
                    heart = queue.getFirst();
                    // Le cœur entier : le chunk primaire d'abord, puis les 8 autour
                    for (Plot core : LandPlan.coreSquare(heart)) {
                        if (!queue.contains(core)) {
                            queue.add(core);
                        }
                    }
                }
                int taken = 0;
                double total = 0;
                String refusal = null;
                boolean heartSet = fresh.land().heart() != null && owned.contains(fresh.land().heart());
                for (Plot plot : queue) {
                    Quote quote = quote(fresh, plot, owned, heart);
                    if (!quote.allowed()) {
                        // Les chunks du cœur pris automatiquement : un chunk déjà pris autour n'arrête rien
                        if (taken > 0 && LandPlan.isCore(heart, plot) && !wanted.contains(plot)) {
                            continue;
                        }
                        refusal = quote.refusal();
                        break;
                    }
                    if (quote.price() > 0 && !clans.takeReserve(clan.id(), quote.price())) {
                        refusal = "claim.reserve-not-enough";
                        break;
                    }
                    if (!claims.claim(plot.server(), plot.world(), plot.x(), plot.z(), clan.id(), System.currentTimeMillis(), quote.price())) {
                        clans.addReserve(clan.id(), quote.price()); // pris entre-temps : remboursé
                        refusal = "claim.taken";
                        break;
                    }
                    if (!heartSet) {
                        clans.setHeart(clan.id(), heart, false);
                        heartSet = true;
                    }
                    owned.add(plot);
                    taken++;
                    total += quote.price();
                    league.add(clan.id(), LeagueStore.Stat.EXPANSION, 1);
                    journal.add(clan.id(), Journal.Category.CLAN, actor, "claim", plot.world(), String.valueOf(plot.x()),
                            String.valueOf(plot.z()), quote.kind().name().toLowerCase(), Money.format(quote.price()));
                    if (quote.price() > 0) {
                        journal.add(clan.id(), Journal.Category.BANK, actor, "bank-claim", Money.format(quote.price()));
                    }
                }
                if (taken > 0) {
                    sync.claimsChanged();
                    sync.clanChanged(clan.id());
                }
                return new Object[]{taken, total, refusal};
            }, result -> {
                int taken = (int) result[0];
                double total = (double) result[1];
                String refusal = (String) result[2];
                if (taken > 0) {
                    messages.send(player, "claim.claimed-many", "count", String.valueOf(taken), "price", Money.format(total));
                    player.playSound(player, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.2f);
                    announce(clan, "claim.announce", "player", player.getName(), "count", String.valueOf(taken));
                    showBorders(player);
                }
                if (refusal != null) {
                    messages.send(player, refusal, "price", Money.format(total), "max", String.valueOf(maxOutposts(clan)),
                            "size", String.valueOf(rules.outpostSize()), "buffer", String.valueOf(rules.buffer()));
                }
                after.run();
            }, () -> messages.send(player, "error.generic"));
        });
    }

    // ---------- Rendre ----------

    public void unclaim(Player player) {
        unclaim(player, plot(ChunkKey.of(player.getLocation())), () -> { });
    }

    /** Rendre un chunk : une part de son prix revient dans la réserve. */
    public void unclaim(Player player, Plot plot, Runnable after) {
        clanService.clanWith(player, ClanPermission.CLAIM).ifPresent(clan -> {
            Land land = clan.land();
            var refusal = LandPlan.unclaim(land.heart(), land.plots(), plot);
            if (refusal.isPresent()) {
                messages.send(player, refusal.get());
                return;
            }
            List<Zone> onChunk = sync.zones().ofClan(clan.id()).stream()
                    .filter(zone -> zone.server().equals(plot.server()) && zone.overlapsChunk(plot.world(), plot.x(), plot.z())).toList();
            if (onChunk.stream().anyMatch(Zone::isRented)) {
                messages.send(player, "claim.rented-zone");
                return;
            }
            String actor = player.getName();
            Tasks.async(plugin, player, () -> {
                double refund = pricing.refundOf(claims.paidOf(plot.server(), plot.world(), plot.x(), plot.z(), clan.id()));
                if (!claims.unclaim(plot.server(), plot.world(), plot.x(), plot.z(), clan.id())) {
                    return -1.0;
                }
                if (refund > 0) {
                    clans.addReserve(clan.id(), refund);
                    journal.add(clan.id(), Journal.Category.BANK, actor, "bank-refund", Money.format(refund));
                }
                if (land.plots().size() == 1) {
                    clans.clearHeart(clan.id());
                }
                league.add(clan.id(), LeagueStore.Stat.EXPANSION, -1);
                onChunk.forEach(zone -> zones.delete(zone.id()));
                journal.add(clan.id(), Journal.Category.CLAN, actor, "unclaim", plot.world(), String.valueOf(plot.x()), String.valueOf(plot.z()));
                sync.claimsChanged();
                sync.zonesChanged();
                sync.clanChanged(clan.id());
                return refund;
            }, refund -> {
                messages.send(player, refund < 0 ? "claim.not-yours" : "claim.unclaimed", "refund", Money.format(Math.max(0, refund)));
                after.run();
            }, () -> messages.send(player, "error.generic"));
        });
    }

    /** Rendre tout le terrain (cœur compris) : remboursé en partie ; pas avec une parcelle louée. Le chef seulement. */
    public void abandon(Player player, Runnable after) {
        clanService.clanOf(player).ifPresent(clan -> {
            if (!clan.isOwner(player.getUniqueId())) {
                messages.send(player, "clan.owner-only");
                return;
            }
            if (sync.zones().ofClan(clan.id()).stream().anyMatch(Zone::isRented)) {
                messages.send(player, "claim.rented-zone");
                return;
            }
            String actor = player.getName();
            Tasks.async(plugin, player, () -> {
                Clan fresh = clans.byId(clan.id()).orElse(clan);
                double refund = 0;
                for (Plot plot : fresh.land().plots()) {
                    refund += pricing.refundOf(claims.paidOf(plot.server(), plot.world(), plot.x(), plot.z(), clan.id()));
                    claims.unclaim(plot.server(), plot.world(), plot.x(), plot.z(), clan.id());
                }
                zones.ofClan(clan.id()).forEach(zone -> zones.delete(zone.id()));
                clans.clearHeart(clan.id());
                if (refund > 0) {
                    clans.addReserve(clan.id(), refund);
                }
                league.add(clan.id(), LeagueStore.Stat.EXPANSION, -fresh.chunks());
                journal.add(clan.id(), Journal.Category.CLAN, actor, "abandon", String.valueOf(fresh.chunks()));
                sync.claimsChanged();
                sync.zonesChanged();
                sync.clanChanged(clan.id());
                return refund;
            }, refund -> {
                messages.send(player, "claim.abandoned", "refund", Money.format(refund));
                after.run();
            }, () -> messages.send(player, "error.generic"));
        });
    }

    // ---------- Cœur et home ----------

    /** Le chunk primaire ici (un chunk de la base) : payant, une fois tous les heart-move-days jours. Le chef seulement. */
    public void moveHeart(Player player, Runnable after) {
        clanService.clanOf(player).ifPresent(clan -> {
            if (!clan.isOwner(player.getUniqueId())) {
                messages.send(player, "clan.owner-only");
                return;
            }
            Land land = clan.land();
            Plot here = plot(ChunkKey.of(player.getLocation()));
            if (!LandPlan.base(land.heart(), land.plots()).contains(here)) {
                messages.send(player, "claim.heart-not-base");
                return;
            }
            long wait = land.heartMovedAt() + rules.heartMoveDays() * DAY_MILLIS - System.currentTimeMillis();
            if (wait > 0) {
                messages.send(player, "claim.heart-wait", "days", String.valueOf((wait + DAY_MILLIS - 1) / DAY_MILLIS));
                return;
            }
            String actor = player.getName();
            Tasks.async(plugin, player, () -> {
                if (rules.heartMovePrice() > 0 && !clans.takeReserve(clan.id(), rules.heartMovePrice())) {
                    return false;
                }
                clans.setHeart(clan.id(), here, true);
                journal.add(clan.id(), Journal.Category.CLAN, actor, "heart", here.world(), String.valueOf(here.x()), String.valueOf(here.z()));
                if (rules.heartMovePrice() > 0) {
                    journal.add(clan.id(), Journal.Category.BANK, actor, "bank-heart", Money.format(rules.heartMovePrice()));
                }
                sync.clanChanged(clan.id());
                return true;
            }, moved -> {
                messages.send(player, moved ? "claim.heart-moved" : "claim.reserve-not-enough", "price",
                        Money.format(rules.heartMovePrice()));
                if (moved) {
                    showBorders(player);
                }
                after.run();
            }, () -> messages.send(player, "error.generic"));
        });
    }

    /** /clan sethome : le home du clan ici, sur son terrain (CLAIM). */
    public void setHome(Player player) {
        clanService.clanWith(player, ClanPermission.CLAIM).ifPresent(clan -> {
            if (sync.claims().owner(player.getLocation()).orElse(-1) != clan.id()) {
                messages.send(player, "home.not-on-land");
                return;
            }
            Location at = player.getLocation();
            Land.Home home = new Land.Home(sync.server(), at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
            String actor = player.getName();
            Tasks.async(plugin, player, () -> {
                clans.setHome(clan.id(), home);
                journal.add(clan.id(), Journal.Category.CLAN, actor, "home", home.world(), String.valueOf((int) home.x()),
                        String.valueOf((int) home.y()), String.valueOf((int) home.z()));
                sync.clanChanged(clan.id());
                return true;
            }, done -> messages.send(player, "home.set"), () -> messages.send(player, "error.generic"));
        });
    }

    /** /clan home : vers le home du clan, avec les règles de téléportation d'EterLib (combat, attente, délai). */
    public void home(Player player) {
        clanService.clanOf(player).ifPresent(clan -> {
            Land.Home home = clan.land().home();
            if (home == null) {
                messages.send(player, "home.none");
                return;
            }
            EterLib.get().getTeleports().teleport(player, Destination.at(home.server(), home.world(), home.x(), home.y(), home.z(),
                    home.yaw(), home.pitch(), clan.name()));
        });
    }

    // ---------- Affichage ----------

    /** Ce qu'il y a ici, et le prix du prochain chunk du clan du joueur. */
    public void here(Player player) {
        OptionalLong owner = sync.claims().owner(player.getLocation());
        String name = owner.isEmpty() ? null : sync.cache().get(owner.getAsLong()).map(Clan::name).orElse("?");
        messages.send(player, name == null ? "claim.here-wild" : "claim.here", "clan", name == null ? "" : name);
        sync.cache().of(player.getUniqueId()).ifPresent(clan -> {
            Plot plot = plot(ChunkKey.of(player.getLocation()));
            Quote quote = quote(clan, plot, clan.land().plots(), clan.land().heart());
            Upkeep upkeep = upkeep(clan);
            messages.send(player, "claim.here-quote", "chunks", String.valueOf(clan.chunks()),
                    "kind", quote.allowed() ? messages.plain(player, "kind." + quote.kind().name().toLowerCase()) : "-",
                    "price", quote.allowed() ? Money.format(quote.price()) : "-", "upkeep", Money.format(upkeep.total()));
        });
        showBorders(player);
    }

    /**
     * Bords des terrains autour du joueur pendant 10 s : une ligne de particules là où deux chunks voisins n'ont pas le
     * même propriétaire (or : le cœur de son clan ; vert : sa base ; bleu : ses avant-postes ; rouge : un autre clan).
     */
    public void showBorders(Player player) {
        Clan own = sync.cache().of(player.getUniqueId()).orElse(null);
        long ownId = own == null ? -1L : own.id();
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
                    Color color = Color.RED;
                    if (owner.getAsLong() == ownId) {
                        Kind kind = LandPlan.kindOf(own.land().heart(), own.land().plots(), plot(chunk));
                        color = kind == Kind.CORE ? Color.fromRGB(255, 200, 40) : kind == Kind.EXTENSION ? Color.LIME : Color.AQUA;
                    }
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

    /** Prévenir tous les membres, où qu'ils soient (thread principal). */
    public void announce(Clan clan, String key, String... placeholders) {
        clan.members().forEach(member -> sync.bus().notify(member.uuid(), key, false, placeholders));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Pricing pricing() {
        return pricing;
    }

    public LandRules rules() {
        return rules;
    }

    public ClanSync sync() {
        return sync;
    }
}
