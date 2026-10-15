package fr.eternom.eterClan.module.ranking;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;

import java.util.List;
import java.util.Map;

/**
 * Le classement des clans du mois, en base (bloquant : hors du thread principal) :
 * - eterclan_monthly : ce que chaque clan a fait ce mois-ci, en net (argent versé moins repris dans la réserve, en
 *   centimes ; chunks pris moins rendus ou perdus) ;
 * - eterclan_league : la dernière photo du classement de chaque mois, écrite par le serveur qui le calcule et lue
 *   par tous (c'est elle qui sert aux récompenses du mois fini) ;
 * - eterclan_league_rewards : un mois déjà récompensé (une seule fois, même avec plusieurs serveurs).
 */
public class LeagueStore {

    private static final String MONTHLY = "monthly";
    private static final String LEAGUE = "league";
    private static final String REWARDS = "league_rewards";

    /** Ce que compte eterclan_monthly. */
    public enum Stat { TREASURY, EXPANSION }

    private final Database database;

    public LeagueStore(Database database) {
        this.database = database;
        database.createTable(MONTHLY,
                Column.of("clan_id", Column.Type.LONG).primaryKey(),
                Column.of("season", Column.Type.STRING).length(7).primaryKey(),
                Column.of("stat", Column.Type.STRING).length(16).primaryKey(),
                Column.of("amount", Column.Type.LONG).notNull());
        database.createTable(LEAGUE,
                Column.of("season", Column.Type.STRING).length(7).primaryKey(),
                Column.of("clan_id", Column.Type.LONG).primaryKey(),
                Column.of("place", Column.Type.INT).notNull(),
                Column.of("score", Column.Type.LONG).notNull(),
                Column.of("activity", Column.Type.LONG).notNull(),
                Column.of("work", Column.Type.LONG).notNull(),
                Column.of("treasury", Column.Type.LONG).notNull(),
                Column.of("expansion", Column.Type.LONG).notNull());
        database.createTable(REWARDS,
                Column.of("season", Column.Type.STRING).length(7).primaryKey(),
                Column.of("given_at", Column.Type.LONG).notNull());
    }

    public void add(long clanId, String season, Stat stat, long amount) {
        database.execute("INSERT INTO " + database.table(MONTHLY) + " (clan_id, season, stat, amount) VALUES (?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE amount = amount + ?", clanId, season, stat.name(), amount, amount);
    }

    public long amount(long clanId, String season, Stat stat) {
        return database.getFirst(MONTHLY, Map.of("clan_id", clanId, "season", season, "stat", stat.name()))
                .map(row -> row.getLong("amount")).orElse(0L);
    }

    /** Remplace la photo du mois. */
    public void save(String season, List<Standing> standings) {
        database.delete(LEAGUE, Map.of("season", season));
        for (Standing standing : standings) {
            database.insert(LEAGUE, Map.of("season", season, "clan_id", standing.clanId(), "place", standing.place(),
                    "score", standing.score(), "activity", standing.activity(), "work", standing.work(),
                    "treasury", standing.treasury(), "expansion", standing.expansion()));
        }
    }

    /** La photo d'un mois, de la première place à la dernière (noms à compléter : ils changent). */
    public List<Standing> load(String season) {
        return database.query("SELECT * FROM " + database.table(LEAGUE) + " WHERE season = ? ORDER BY place, clan_id", season).stream()
                .map(row -> new Standing(row.getLong("clan_id"), row.getInt("place"), row.getLong("score"), row.getLong("activity"),
                        row.getLong("work"), row.getLong("treasury"), row.getLong("expansion")))
                .toList();
    }

    /** @return true pour le seul serveur qui récompense ce mois */
    public boolean claimRewards(String season) {
        return database.execute("INSERT IGNORE INTO " + database.table(REWARDS) + " (season, given_at) VALUES (?, ?)",
                season, System.currentTimeMillis()) > 0;
    }
}
