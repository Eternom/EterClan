package fr.eternom.eterClan.module.bank;

import fr.eternom.eterClan.module.claim.ClaimRepository;
import fr.eternom.eterClan.module.claim.ClaimRepository.Claim;
import fr.eternom.eterClan.module.claim.LandPlan;
import fr.eternom.eterClan.module.claim.LandPlan.Plot;
import fr.eternom.eterClan.module.claim.LandRules;
import fr.eternom.eterClan.module.claim.Pricing;
import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.Clan.Member;
import fr.eternom.eterClan.module.clan.ClanRepository;
import fr.eternom.eterClan.module.clan.Journal;
import fr.eternom.eterClan.module.clan.Land;
import fr.eternom.eterClan.module.ranking.ClanLeague;
import fr.eternom.eterClan.module.ranking.LeagueStore;
import fr.eternom.eterClan.module.sync.ClanSync;
import fr.eternom.eterClan.module.zone.Zone;
import fr.eternom.eterClan.module.zone.ZoneRepository;
import fr.eternom.eterEconomy.api.EconomyApi;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.module.player.PlayerDirectory.NetworkPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Le passage hebdomadaire de chaque clan, les loyers et les rappels. Vérifié toutes les 10 min en tâche de fond, sur
 * chaque serveur : le premier qui réserve un passage en base (requête conditionnelle) le fait, les autres l'ignorent.
 * Les rappels tombent dans une fenêtre de 10 min fixe (une seule fois, sans rien retenir).
 *
 * 1. Intérêt : interest % de chaque compte de membre passe dans la réserve.
 * 2. Entretien (Pricing, réduit pour le top 3 du mois), plus la dette éventuelle, payé au serveur par la réserve (et
 *    les comptes des membres au prorata si land.upkeep-from-accounts). Pas assez : la dette grossit ; au bout de
 *    land.debt-grace-days jours de dette, le clan perd des chunks (LandPlan#lossOrder : avant-postes, puis les
 *    extensions les plus loin du cœur, sans couper la base, jamais le cœur) jusqu'à pouvoir payer.
 *    Rappels : 3 jours avant le passage si la réserve ne suffit pas ; en dette, 7, 3 et 1 jour(s) avant la perte.
 * 3. Clan inactif (aucun membre venu) : chunks payants perdus après land.inactive-paid-days jours, tout le terrain
 *    après land.inactive-all-days ; le staff (eterclan.admin) est prévenu une semaine avant.
 * Loyers : chaque parcelle louée encaisse son loyer sur le porte-monnaie du locataire (même hors ligne) dans la
 * réserve ; rappel 2 jours avant ; s'il ne peut pas payer, une semaine de grâce (deux loyers la fois suivante), puis la
 * location s'arrête. Une parcelle perdue avec son chunk : le locataire est remboursé de ce qu'il restait.
 */
public class WeeklyCycle {

    private static final long CHECK_TICKS = 10 * 60 * 20;
    private static final long WINDOW_MILLIS = 10 * 60 * 1000L;
    private static final long DAY_MILLIS = 86_400_000L;
    static final Duration WEEK = Duration.ofDays(7);

    private final JavaPlugin plugin;
    private final ClanRepository clans;
    private final ClaimRepository claims;
    private final ZoneRepository zones;
    private final ClanSync sync;
    private final Pricing pricing;
    private final LandRules rules;
    private final Journal journal;
    private final boolean fromAccounts;
    private final boolean landServer;
    /** Chunks perdus faute d'entretien : retirés du classement du mois (expansion). */
    private final ClanLeague league;

    public WeeklyCycle(JavaPlugin plugin, ClanRepository clans, ClaimRepository claims, ZoneRepository zones, ClanSync sync,
                       Pricing pricing, LandRules rules, Journal journal, ClanLeague league) {
        this.league = league;
        this.plugin = plugin;
        this.clans = clans;
        this.claims = claims;
        this.zones = zones;
        this.sync = sync;
        this.pricing = pricing;
        this.rules = rules;
        this.journal = journal;
        this.fromAccounts = plugin.getConfig().getBoolean("land.upkeep-from-accounts", false);
        this.landServer = plugin.getConfig().getBoolean("land.enabled", false);
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::run, 20 * 60, CHECK_TICKS);
    }

    private void run() {
        long now = System.currentTimeMillis();
        try {
            // Les rappels : une seule fois pour tout le réseau, depuis le serveur qui a le terrain (la survie)
            if (landServer) {
                for (long id : clans.ids()) {
                    clans.byId(id).ifPresent(clan -> reminders(clan, now));
                }
                remindRents(now);
            }
            for (long id : clans.dueCycles(now)) {
                clans.byId(id).ifPresent(clan -> {
                    long next = Math.max(clan.nextCycle() + WEEK.toMillis(), now + 60_000);
                    if (clans.claimCycle(id, clan.nextCycle(), next)) {
                        cycle(clan, now);
                    }
                });
            }
            EconomyApi economy = EconomyApi.get().orElse(null);
            if (economy != null) {
                zones.dueRents(now).forEach(zone -> rent(zone, economy, now));
            }
            clans.purgePasses(now);
            journal.purge();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Passage hebdomadaire des clans", e);
        }
    }

    /** Vrai une seule fois : quand moment tombe dans les 10 dernières minutes. */
    private static boolean due(long moment, long now) {
        return moment <= now && moment > now - WINDOW_MILLIS;
    }

    private int rank(Clan clan) {
        return league.of(clan.id()).map(named -> named.standing().place()).orElse(0);
    }

    private double upkeepOf(Clan clan) {
        int[] paid = LandPlan.paidCounts(clan.land().heart(), clan.land().plots());
        return pricing.upkeepOf(paid[0], paid[1], rank(clan));
    }

    // ---------- Rappels ----------

    private void reminders(Clan clan, long now) {
        double upkeep = upkeepOf(clan);
        Land land = clan.land();
        double available = clan.reserve() + (fromAccounts ? clan.accounts() : 0);
        // 3 jours avant le passage : la réserve ne suffira pas
        if (upkeep + land.debt() > available && due(clan.nextCycle() - 3 * DAY_MILLIS, now)) {
            announce(clan, "bank.upkeep-warning", "amount", Money.format(upkeep + land.debt()), "reserve", Money.format(clan.reserve()));
        }
        // En dette : 7, 3 et 1 jour(s) avant la perte de chunks
        if (land.debtSince() > 0) {
            long loss = land.debtSince() + rules.debtGraceDays() * DAY_MILLIS;
            for (int days : new int[]{7, 3, 1}) {
                if (due(loss - days * DAY_MILLIS, now)) {
                    announce(clan, "bank.debt-warning", "days", String.valueOf(days), "debt", Money.format(land.debt()));
                }
            }
        }
        // Inactivité : le staff, une semaine avant chaque seuil
        long inactive = now - lastActivity(clan);
        for (int days : new int[]{rules.inactivePaidDays(), rules.inactiveAllDays()}) {
            if (days > 0 && clan.chunks() > 0 && due(lastActivity(clan) + (days - 7) * DAY_MILLIS, now)) {
                staff("staff.inactive-warning", "clan", clan.name(), "days", String.valueOf(inactive / DAY_MILLIS),
                        "limit", String.valueOf(days));
            }
        }
    }

    private void remindRents(long now) {
        for (Zone zone : zones.dueRents(now + 2 * DAY_MILLIS)) {
            if (due(zone.rentDue() - 2 * DAY_MILLIS, now)) {
                Bukkit.getScheduler().runTask(plugin, () -> sync.bus().notify(zone.tenant(), "zone.rent-reminder", true,
                        "zone", zone.name(), "rent", Money.format(zone.rent())));
            }
        }
    }

    // ---------- Passage ----------

    private void cycle(Clan clan, long now) {
        // 1. Intérêt : des comptes vers la réserve
        double interest = 0;
        for (Member member : clan.members()) {
            double cut = cents(member.account() * clan.interest() / 100);
            if (cut > 0 && clans.withdraw(member.uuid(), cut)) {
                interest += cut;
            }
        }
        if (interest > 0) {
            clans.addReserve(clan.id(), interest);
            journal.add(clan.id(), Journal.Category.BANK, "", "bank-interest", Money.format(interest));
        }

        // 3. Inactivité (avant l'entretien : un clan abandonné ne s'endette pas)
        long inactiveDays = (now - lastActivity(clan)) / DAY_MILLIS;
        if (rules.inactiveAllDays() > 0 && inactiveDays >= rules.inactiveAllDays() && clan.chunks() > 0) {
            lose(clan, clan.chunks(), true, "inactive");
            clans.clearHeart(clan.id());
            staff("staff.inactive-freed", "clan", clan.name(), "count", String.valueOf(clan.chunks()));
            sync.clanChanged(clan.id());
            return;
        }
        if (rules.inactivePaidDays() > 0 && inactiveDays >= rules.inactivePaidDays()) {
            int[] paid = LandPlan.paidCounts(clan.land().heart(), clan.land().plots());
            if (paid[0] + paid[1] > 0) {
                lose(clan, paid[0] + paid[1], false, "inactive");
                staff("staff.inactive-freed", "clan", clan.name(), "count", String.valueOf(paid[0] + paid[1]));
                clan = clans.byId(clan.id()).orElse(clan);
            }
        }

        // 2. Entretien et dette, payés au serveur
        Clan current = clans.byId(clan.id()).orElse(clan);
        double owed = upkeepOf(current) + current.land().debt();
        if (owed <= 0) {
            sync.clanChanged(clan.id());
            return;
        }
        if (pay(current.id(), owed)) {
            clans.setDebt(current.id(), 0, 0);
            journal.add(current.id(), Journal.Category.BANK, "", "bank-upkeep", Money.format(owed));
        } else if (current.land().debtSince() > 0 && now - current.land().debtSince() >= rules.debtGraceDays() * DAY_MILLIS) {
            // Fin du délai : des chunks perdus jusqu'à pouvoir payer ce qui reste (la dette est effacée)
            loseUntilAffordable(current);
            clans.setDebt(current.id(), 0, 0);
        } else {
            double upkeep = upkeepOf(current);
            clans.setDebt(current.id(), current.land().debt() + upkeep, current.land().debtSince() > 0 ? current.land().debtSince() : now);
            journal.add(current.id(), Journal.Category.BANK, "", "bank-debt", Money.format(current.land().debt() + upkeep));
            Clan notified = current;
            Bukkit.getScheduler().runTask(plugin, () -> announce(notified, "bank.debt-started", "debt",
                    Money.format(notified.land().debt() + upkeep), "days", String.valueOf(rules.debtGraceDays())));
        }
        sync.clanChanged(clan.id());
    }

    /** Perd le moins de chunks possible pour que la réserve paie l'entretien de ce qui reste. */
    private void loseUntilAffordable(Clan clan) {
        double available = clan.reserve() + (fromAccounts ? clan.accounts() : 0);
        Land land = clan.land();
        List<Plot> order = LandPlan.lossOrder(land.heart(), land.plots(), clan.chunks());
        Set<Plot> rest = new HashSet<>(land.plots());
        int count = 0;
        for (Plot plot : order) {
            int[] paid = LandPlan.paidCounts(land.heart(), rest);
            if (pricing.upkeepOf(paid[0], paid[1], rank(clan)) <= available) {
                break;
            }
            rest.remove(plot);
            count++;
        }
        lose(clan, count, false, "upkeep");
        int[] paid = LandPlan.paidCounts(land.heart(), rest);
        double upkeep = pricing.upkeepOf(paid[0], paid[1], rank(clan));
        if (upkeep > 0 && pay(clan.id(), upkeep)) {
            journal.add(clan.id(), Journal.Category.BANK, "", "bank-upkeep", Money.format(upkeep));
        }
    }

    /**
     * Perd count chunks dans l'ordre de LandPlan (all : tout, cœur compris). Les parcelles dessus disparaissent ; un
     * locataire est remboursé (pris sur la réserve) de la part de semaine qu'il avait payée.
     */
    private void lose(Clan clan, int count, boolean all, String reason) {
        Land land = clan.land();
        List<Plot> lost = all ? List.copyOf(land.plots()) : LandPlan.lossOrder(land.heart(), land.plots(), count);
        if (lost.isEmpty()) {
            return;
        }
        List<Zone> clanZones = zones.ofClan(clan.id());
        long now = System.currentTimeMillis();
        for (Plot plot : lost) {
            claims.remove(new Claim(plot.server(), plot.world(), plot.x(), plot.z(), clan.id(), 0));
            clanZones.stream().filter(zone -> zone.server().equals(plot.server()) && zone.overlapsChunk(plot.world(), plot.x(), plot.z()))
                    .forEach(zone -> {
                        if (zone.isRented()) {
                            double refund = cents(zone.rent() * Math.min(1, Math.max(0, zone.rentDue() - now) / (double) WEEK.toMillis()));
                            if (refund > 0 && clans.takeReserve(clan.id(), refund)) {
                                EconomyApi.get().ifPresent(economy -> economy.deposit(zone.tenant(), refund, "EterClan · loyer"));
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> sync.bus().notify(zone.tenant(), "zone.lost-refunded", true,
                                    "zone", zone.name(), "refund", Money.format(refund)));
                        }
                        zones.deleteAnyway(zone.id());
                    });
        }
        sync.claimsChanged();
        sync.zonesChanged();
        league.add(clan.id(), LeagueStore.Stat.EXPANSION, -lost.size());
        journal.add(clan.id(), Journal.Category.CLAN, "", "lost", String.valueOf(lost.size()), reason);
        int size = lost.size();
        Bukkit.getScheduler().runTask(plugin, () -> announce(clan, "bank.chunks-lost", "count", String.valueOf(size)));
    }

    /**
     * La réserve d'abord, puis (si land.upkeep-from-accounts) les comptes au prorata ; false (rien n'est pris) si le tout
     * ne suffit pas. La réserve peut bouger pendant le calcul : relue et retentée quelques fois avant de conclure.
     */
    private boolean pay(long clanId, double amount) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Clan clan = clans.byId(clanId).orElse(null);
            if (clan == null || clan.reserve() + (fromAccounts ? clan.accounts() : 0) < amount) {
                return false;
            }
            double fromReserve = Math.min(clan.reserve(), amount);
            if (fromReserve <= 0 || clans.takeReserve(clanId, fromReserve)) {
                if (amount - fromReserve > 0) {
                    payFromAccounts(clan, amount - fromReserve);
                }
                return true;
            }
        }
        return false;
    }

    /** Le reste de l'entretien, pris sur les comptes au prorata de ce qu'ils contiennent. */
    private void payFromAccounts(Clan clan, double rest) {
        double accounts = clan.accounts();
        for (Member member : clan.members()) {
            if (rest <= 0 || accounts <= 0) {
                break;
            }
            double share = Math.min(member.account(), Math.ceil(rest * member.account() / accounts * 100) / 100);
            if (share > 0) {
                clans.withdraw(member.uuid(), share);
            }
        }
    }

    /** Dernière venue d'un membre sur le réseau (0 si aucun n'est jamais venu). */
    private static long lastActivity(Clan clan) {
        long last = 0;
        for (Member member : clan.members()) {
            last = Math.max(last, EterLib.get().getPlayers().get(member.uuid()).map(NetworkPlayer::lastSeen).orElse(0L));
            last = Math.max(last, member.joinedAt());
        }
        return last;
    }

    /**
     * Loyer dû : sur le porte-monnaie du locataire. Pas assez : une semaine de grâce (le loyer suivant compte double),
     * puis la location s'arrête.
     */
    private void rent(Zone zone, EconomyApi economy, long now) {
        long next = Math.max(zone.rentDue() + WEEK.toMillis(), now + 60_000);
        if (!zones.claimRent(zone.id(), zone.rentDue(), next)) {
            return;
        }
        boolean late = zones.isLate(zone.id());
        double amount = zone.rent() * (late ? 2 : 1);
        if (economy.withdraw(zone.tenant(), amount, "EterClan · loyer")) {
            clans.addReserve(zone.clanId(), amount);
            zones.setLate(zone.id(), false);
            journal.add(zone.clanId(), Journal.Category.BANK, zone.tenantName(), "bank-rent", Money.format(amount), zone.name());
            return;
        }
        if (!late) {
            zones.setLate(zone.id(), true);
            Bukkit.getScheduler().runTask(plugin, () -> sync.bus().notify(zone.tenant(), "zone.rent-late", true,
                    "zone", zone.name(), "rent", Money.format(zone.rent() * 2)));
            return;
        }
        zones.setLate(zone.id(), false);
        zones.release(zone.id());
        sync.zonesChanged();
        Bukkit.getScheduler().runTask(plugin, () ->
                sync.bus().notify(zone.tenant(), "zone.rent-failed", true, "zone", zone.name()));
    }

    /** Prévenir tous les membres, où qu'ils soient (thread principal). */
    private void announce(Clan clan, String key, String... placeholders) {
        Runnable send = () -> clan.members().forEach(member -> sync.bus().notify(member.uuid(), key, true, placeholders));
        if (Bukkit.isPrimaryThread()) {
            send.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, send);
        }
    }

    /** Le staff de ce serveur (eterclan.admin) et la console. */
    private void staff(String key, String... placeholders) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            var messages = sync.messages();
            Bukkit.getConsoleSender().sendMessage(messages.get(Bukkit.getConsoleSender(), key, placeholders));
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("eterclan.admin")) {
                    messages.send(player, key, placeholders);
                }
            }
        });
    }

    static double cents(double amount) {
        return Math.floor(amount * 100) / 100;
    }
}
