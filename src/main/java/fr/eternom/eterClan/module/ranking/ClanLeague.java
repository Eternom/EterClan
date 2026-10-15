package fr.eternom.eterClan.module.ranking;

import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanRepository;
import fr.eternom.eterClan.module.ranking.LeagueStore.Stat;
import fr.eternom.eterClan.module.sync.ClanSync;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.ranking.RankingSource;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToLongFunction;

/**
 * Le classement des clans du mois, comme le classement général des joueurs : 4 critères du mois, dans chacun une note
 * sur 100 selon la PLACE (1er sur N = 100, dernier = 100/N, rien fait = 0), le score est leur moyenne.
 * - Activité : minutes de jeu actif des membres (EterReward, source monthly_playtime) ;
 * - Travail : quêtes et défis réussis par les membres (monthly_quests, monthly_challenges) ;
 * - Trésor : argent versé dans la réserve, moins ce qui en est repris (donner puis reprendre ne rapporte rien) ;
 * - Expansion : chunks pris, moins ceux rendus ou perdus (prendre puis rendre les chunks gratuits ne rapporte rien).
 * L'activité des membres vient d'EterReward par EterLib (RankingSource) : sans lui, ces critères valent 0.
 *
 * Calculé toutes les 5 minutes par les serveurs qui ont du terrain (la survie), photographié en base ; les autres
 * serveurs lisent la photo. Le mois fini, le top 3 reçoit son prix dans la réserve, une seule fois.
 */
public class ClanLeague {

    private static final DateTimeFormatter SEASON = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final long REFRESH_TICKS = 20 * 60 * 5;

    /** Nom affiché d'un clan classé. */
    public record Named(Standing standing, String name, String tag, UUID owner) {
    }

    private final JavaPlugin plugin;
    private final LeagueStore store;
    private final ClanRepository clans;
    private final ClanSync sync;
    private final ZoneId zone;
    private final boolean computes;
    private final List<Double> rewards = new ArrayList<>();
    private volatile List<Named> current = List.of();

    public ClanLeague(JavaPlugin plugin, LeagueStore store, ClanRepository clans, ClanSync sync, boolean computes) {
        this.plugin = plugin;
        this.store = store;
        this.clans = clans;
        this.sync = sync;
        this.computes = computes;
        ConfigurationSection config = plugin.getConfig().getConfigurationSection("ranking");
        ZoneId read;
        try {
            read = ZoneId.of(config == null ? "Europe/Paris" : config.getString("time-zone", "Europe/Paris"));
        } catch (RuntimeException e) {
            read = ZoneId.of("Europe/Paris");
        }
        this.zone = read;
        List<Double> configured = config == null ? List.of() : config.getDoubleList("rewards");
        (configured.isEmpty() ? List.of(3000.0, 2000.0, 1000.0) : configured).forEach(rewards::add);
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refresh, 20 * 15, REFRESH_TICKS);
    }

    public String season() {
        return YearMonth.now(zone).format(SEASON);
    }

    /** Fin du mois en cours (ms). */
    public long seasonEnd() {
        return YearMonth.now(zone).plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    public List<Double> rewards() {
        return List.copyOf(rewards);
    }

    /** Ce que le clan a fait ce mois-ci (argent en centimes). Bloquant ; à appeler des tâches de fond existantes. */
    public void add(long clanId, Stat stat, long amount) {
        if (amount != 0) {
            store.add(clanId, season(), stat, amount);
        }
    }

    /** Le classement du mois (dernière photo), du premier au dernier. */
    public List<Named> current() {
        return current;
    }

    public Optional<Named> of(long clanId) {
        return current.stream().filter(named -> named.standing().clanId() == clanId).findFirst();
    }

    /** Toutes les 5 minutes (tâche de fond). */
    private void refresh() {
        try {
            String season = season();
            if (computes) {
                store.save(season, compute(season));
                giveRewards();
            }
            current = name(store.load(season));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Classement des clans non calculé : " + e.getMessage());
        }
    }

    private List<Standing> compute(String season) {
        List<long[]> rows = new ArrayList<>(); // {clanId, activity, work, treasury, expansion}
        for (long id : clans.ids()) {
            clans.byId(id).ifPresent(clan -> rows.add(new long[]{clan.id(), sum(clan, "monthly_playtime"),
                    sum(clan, "monthly_quests") + sum(clan, "monthly_challenges"),
                    Math.max(0, store.amount(id, season, Stat.TREASURY)), Math.max(0, store.amount(id, season, Stat.EXPANSION))}));
        }
        Map<Long, Double> total = new HashMap<>();
        for (int criterion = 1; criterion <= 4; criterion++) {
            int column = criterion;
            notes(rows, row -> row[column]).forEach((clan, note) -> total.merge(clan, note, Double::sum));
        }
        List<long[]> ranked = rows.stream().filter(row -> total.getOrDefault(row[0], 0.0) > 0)
                .sorted(Comparator.comparingDouble((long[] row) -> total.get(row[0])).reversed().thenComparingLong(row -> row[0]))
                .toList();
        List<Standing> standings = new ArrayList<>();
        int place = 0;
        long previous = -1;
        for (int i = 0; i < ranked.size(); i++) {
            long[] row = ranked.get(i);
            long score = Math.round(total.get(row[0]) / 4 * 10);
            if (score != previous) {
                place = i + 1;
                previous = score;
            }
            standings.add(new Standing(row[0], place, score, row[1], row[2], row[3], row[4]));
        }
        return standings;
    }

    /** Note sur 100 de chaque clan qui a fait quelque chose dans ce critère (ex aequo : même place). */
    private static Map<Long, Double> notes(List<long[]> rows, ToLongFunction<long[]> value) {
        List<long[]> sorted = rows.stream().filter(row -> value.applyAsLong(row) > 0)
                .sorted(Comparator.comparingLong(value).reversed()).toList();
        Map<Long, Double> notes = new HashMap<>();
        int size = sorted.size();
        int place = 0;
        for (int i = 0; i < size; i++) {
            if (i == 0 || value.applyAsLong(sorted.get(i)) != value.applyAsLong(sorted.get(i - 1))) {
                place = i + 1;
            }
            notes.put(sorted.get(i)[0], 100.0 * (size - place + 1) / size);
        }
        return notes;
    }

    /** Somme d'un classement du mois d'EterReward sur les membres du clan (0 sans EterReward). */
    private static long sum(Clan clan, String source) {
        return RankingSource.find(source).map(found -> clan.members().stream()
                .mapToLong(member -> found.of(member.uuid())[1]).sum()).orElse(0L);
    }

    /** Le mois d'avant, une seule fois : son top 3 (dernière photo) reçoit son prix dans la réserve. */
    private void giveRewards() {
        String previous = YearMonth.now(zone).minusMonths(1).format(SEASON);
        List<Standing> last = store.load(previous);
        if (last.isEmpty() || !store.claimRewards(previous)) {
            return;
        }
        for (Standing standing : last) {
            if (standing.place() > rewards.size()) {
                continue;
            }
            double prize = rewards.get(standing.place() - 1);
            if (prize <= 0) {
                continue;
            }
            clans.addReserve(standing.clanId(), prize);
            sync.clanChanged(standing.clanId());
            clans.byId(standing.clanId()).ifPresent(clan -> Bukkit.getScheduler().runTask(plugin, () -> clan.members().forEach(member ->
                    sync.bus().notify(member.uuid(), "league.won", true, "place", String.valueOf(standing.place()),
                            "amount", Money.format(prize)))));
            plugin.getLogger().info("Classement des clans " + previous + " : clan " + standing.clanId() + " "
                    + standing.place() + "e, " + prize + " versés à sa réserve");
        }
    }

    private List<Named> name(List<Standing> standings) {
        List<Named> named = new ArrayList<>();
        for (Standing standing : standings) {
            clans.byId(standing.clanId()).ifPresent(clan -> named.add(new Named(standing, clan.name(), clan.tag(), clan.owner())));
        }
        return List.copyOf(named);
    }
}
