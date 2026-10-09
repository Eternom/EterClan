package fr.eternom.eterClan.module.bank;

import fr.eternom.eterClan.module.claim.ClaimRepository;
import fr.eternom.eterClan.module.claim.ClaimRepository.Claim;
import fr.eternom.eterClan.module.claim.Pricing;
import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.Clan.Member;
import fr.eternom.eterClan.module.clan.ClanRepository;
import fr.eternom.eterClan.module.sync.ClanSync;
import fr.eternom.eterClan.module.zone.Zone;
import fr.eternom.eterClan.module.zone.ZoneRepository;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterEconomy.api.EconomyApi;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.List;
import java.util.logging.Level;

/**
 * Le passage hebdomadaire de chaque clan, et les loyers. Vérifié toutes les 10 min en tâche de fond, sur chaque
 * serveur : le premier qui réserve un passage en base (requête conditionnelle) le fait, les autres l'ignorent.
 *
 * 1. Intérêt : interest % de chaque compte de membre passe dans la réserve.
 * 2. Entretien des chunks payants (Pricing), payé au serveur : la réserve d'abord, puis les comptes au prorata de ce
 *    qu'ils contiennent ; si tout ne suffit pas, le clan perd ses chunks les plus récents (et les parcelles dessus)
 *    jusqu'à pouvoir payer.
 * Loyers : chaque parcelle louée encaisse son loyer sur le porte-monnaie du locataire (même hors ligne) dans la
 * réserve ; s'il ne peut pas payer, la location s'arrête.
 */
public class WeeklyCycle {

    private static final long CHECK_TICKS = 10 * 60 * 20;
    static final Duration WEEK = Duration.ofDays(7);

    private final JavaPlugin plugin;
    private final ClanRepository clans;
    private final ClaimRepository claims;
    private final ZoneRepository zones;
    private final ClanSync sync;
    private final Pricing pricing;

    public WeeklyCycle(JavaPlugin plugin, ClanRepository clans, ClaimRepository claims, ZoneRepository zones, ClanSync sync,
                       Pricing pricing) {
        this.plugin = plugin;
        this.clans = clans;
        this.claims = claims;
        this.zones = zones;
        this.sync = sync;
        this.pricing = pricing;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::run, 20 * 60, CHECK_TICKS);
    }

    private void run() {
        long now = System.currentTimeMillis();
        try {
            for (long id : clans.dueCycles(now)) {
                clans.byId(id).ifPresent(clan -> {
                    long next = Math.max(clan.nextCycle() + WEEK.toMillis(), now + 60_000);
                    if (clans.claimCycle(id, clan.nextCycle(), next)) {
                        cycle(clan);
                    }
                });
            }
            EconomyApi economy = EconomyApi.get().orElse(null);
            if (economy != null) {
                zones.dueRents(now).forEach(zone -> rent(zone, economy, now));
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Passage hebdomadaire des clans", e);
        }
    }

    private void cycle(Clan clan) {
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
        }

        // 2. Entretien, payé au serveur
        double upkeep = pricing.upkeepOf(clan.chunks());
        if (upkeep > 0 && !pay(clan.id(), upkeep)) {
            loseChunks(clan);
        }
        sync.clanChanged(clan.id());
    }

    /**
     * Réserve d'abord, puis comptes au prorata ; false (rien n'est pris) si le tout ne suffit pas. La réserve peut bouger
     * pendant le calcul (un membre qui retire) : relue et retentée quelques fois avant de conclure.
     */
    private boolean pay(long clanId, double amount) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Clan clan = clans.byId(clanId).orElse(null);
            if (clan == null || clan.reserve() + clan.accounts() < amount) {
                return false;
            }
            double fromReserve = Math.min(clan.reserve(), amount);
            if (fromReserve <= 0 || clans.takeReserve(clanId, fromReserve)) {
                payFromAccounts(clan, amount - fromReserve);
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

    /** Perd les chunks les plus récents jusqu'à pouvoir payer l'entretien de ce qui reste (les gratuits ne coûtent rien). */
    private void loseChunks(Clan clan) {
        double available = clan.reserve() + clan.accounts();
        int keep = clan.chunks();
        while (keep > 0 && pricing.upkeepOf(keep) > available) {
            keep--;
        }
        List<Claim> lost = claims.newest(clan.id(), clan.chunks() - keep);
        List<Zone> clanZones = zones.ofClan(clan.id());
        for (Claim claim : lost) {
            claims.remove(claim);
            clanZones.stream().filter(zone -> zone.server().equals(claim.server())
                            && zone.overlapsChunk(claim.world(), claim.x(), claim.z()))
                    .forEach(zone -> zones.deleteAnyway(zone.id()));
        }
        pay(clan.id(), pricing.upkeepOf(keep));
        sync.claimsChanged();
        sync.zonesChanged();
        int count = lost.size();
        Bukkit.getScheduler().runTask(plugin, () -> clan.members().forEach(member ->
                sync.bus().notify(member.uuid(), "bank.chunks-lost", true, "count", String.valueOf(count))));
    }

    private void rent(Zone zone, EconomyApi economy, long now) {
        long next = Math.max(zone.rentDue() + WEEK.toMillis(), now + 60_000);
        if (!zones.claimRent(zone.id(), zone.rentDue(), next)) {
            return;
        }
        if (economy.withdraw(zone.tenant(), zone.rent(), "EterClan · loyer")) {
            clans.addReserve(zone.clanId(), zone.rent());
            return;
        }
        zones.release(zone.id());
        sync.zonesChanged();
        Bukkit.getScheduler().runTask(plugin, () ->
                sync.bus().notify(zone.tenant(), "zone.rent-failed", true, "zone", zone.name()));
    }

    static double cents(double amount) {
        return Math.floor(amount * 100) / 100;
    }
}
