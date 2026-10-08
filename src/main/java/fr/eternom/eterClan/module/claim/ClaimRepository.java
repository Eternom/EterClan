package fr.eternom.eterClan.module.claim;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;

import java.util.List;
import java.util.Map;

/**
 * Chunks des clans (eterclan_claims) : serveur, monde, coordonnées du chunk, clan, date. Un chunk n'a qu'un clan (clé
 * primaire) : deux clans qui prennent le même chunk en même temps, un seul l'obtient. Bloquant : hors du thread principal.
 */
public class ClaimRepository {

    private static final String TABLE = "claims";

    /** Un chunk du terrain d'un clan. */
    public record Claim(String server, String world, int x, int z, long clanId, long claimedAt) {
    }

    private final Database database;

    public ClaimRepository(Database database) {
        this.database = database;
        database.createTable(TABLE,
                Column.of("server", Column.Type.STRING).length(64).primaryKey(),
                Column.of("world", Column.Type.STRING).length(64).primaryKey(),
                Column.of("x", Column.Type.INT).primaryKey(),
                Column.of("z", Column.Type.INT).primaryKey(),
                Column.of("clan_id", Column.Type.LONG).notNull(),
                Column.of("claimed_at", Column.Type.LONG).notNull());
    }

    /** Tous les chunks de ce serveur (index en mémoire). */
    public List<Claim> onServer(String server) {
        return database.get(TABLE, Map.of("server", server)).stream()
                .map(row -> new Claim(row.getString("server"), row.getString("world"), row.getInt("x"), row.getInt("z"),
                        row.getLong("clan_id"), row.getLong("claimed_at")))
                .toList();
    }

    /** false si le chunk est déjà pris. */
    public boolean claim(String server, String world, int x, int z, long clanId, long now) {
        return database.execute("INSERT IGNORE INTO " + database.table(TABLE) + " (server, world, x, z, clan_id, claimed_at)"
                + " VALUES (?, ?, ?, ?, ?, ?)", server, world, x, z, clanId, now) > 0;
    }

    public boolean unclaim(String server, String world, int x, int z, long clanId) {
        return database.delete(TABLE, Map.of("server", server, "world", world, "x", x, "z", z, "clan_id", clanId)) > 0;
    }

    /** Les count chunks les plus récents du clan, tous serveurs (perdus quand l'entretien n'est pas payé). */
    public List<Claim> newest(long clanId, int count) {
        return database.query("SELECT * FROM " + database.table(TABLE) + " WHERE clan_id = ? ORDER BY claimed_at DESC LIMIT ?",
                        clanId, count).stream()
                .map(row -> new Claim(row.getString("server"), row.getString("world"), row.getInt("x"), row.getInt("z"),
                        row.getLong("clan_id"), row.getLong("claimed_at")))
                .toList();
    }

    public void remove(Claim claim) {
        database.delete(TABLE, Map.of("server", claim.server(), "world", claim.world(), "x", claim.x(), "z", claim.z()));
    }
}
