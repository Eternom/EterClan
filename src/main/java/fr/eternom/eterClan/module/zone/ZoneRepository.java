package fr.eternom.eterClan.module.zone;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Parcelles (eterclan_zones). Louer et payer un loyer sont des requêtes conditionnelles : une parcelle ne se loue
 * qu'une fois, et un loyer n'est encaissé qu'une fois même si deux serveurs passent en même temps.
 * Bloquant : hors du thread principal.
 */
public class ZoneRepository {

    private static final String TABLE = "zones";

    private final Database database;
    private final String table;

    public ZoneRepository(Database database) {
        this.database = database;
        database.createTable(TABLE,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("clan_id", Column.Type.LONG).notNull(),
                Column.of("server", Column.Type.STRING).length(64).notNull(),
                Column.of("world", Column.Type.STRING).length(64).notNull(),
                Column.of("min_x", Column.Type.INT).notNull(),
                Column.of("min_z", Column.Type.INT).notNull(),
                Column.of("max_x", Column.Type.INT).notNull(),
                Column.of("max_z", Column.Type.INT).notNull(),
                Column.of("name", Column.Type.STRING).length(32).notNull(),
                Column.of("rent", Column.Type.DOUBLE).notNull(),
                Column.of("tenant", Column.Type.UUID),
                Column.of("tenant_name", Column.Type.STRING).length(16),
                Column.of("rent_due", Column.Type.LONG).notNull(),
                Column.of("trusted", Column.Type.TEXT));
        this.table = database.table(TABLE);
    }

    public List<Zone> onServer(String server) {
        return database.get(TABLE, Map.of("server", server)).stream().map(ZoneRepository::toZone).toList();
    }

    public List<Zone> ofClan(long clanId) {
        return database.get(TABLE, Map.of("clan_id", clanId)).stream().map(ZoneRepository::toZone).toList();
    }

    /** Loyers dus, tous serveurs. */
    public List<Zone> dueRents(long now) {
        return database.query("SELECT * FROM " + table + " WHERE tenant IS NOT NULL AND rent_due <= ?", now).stream()
                .map(ZoneRepository::toZone).toList();
    }

    public void create(long clanId, String server, String world, int minX, int minZ, int maxX, int maxZ, String name) {
        database.insert(TABLE, Map.of("clan_id", clanId, "server", server, "world", world, "min_x", minX, "min_z", minZ,
                "max_x", maxX, "max_z", maxZ, "name", name, "rent", 0.0, "rent_due", 0L));
    }

    /** Supprimée seulement si elle n'est pas louée. */
    public boolean delete(long id) {
        return database.execute("DELETE FROM " + table + " WHERE id = ? AND tenant IS NULL", id) > 0;
    }

    /** Parcelle d'un chunk perdu : supprimée, même louée (la location s'arrête). */
    public void deleteAnyway(long id) {
        database.delete(TABLE, Map.of("id", id));
    }

    public void setRent(long id, double rent) {
        database.update(TABLE, Map.of("rent", rent), Map.of("id", id));
    }

    /** Loue la parcelle si elle est libre et à louer ; le premier loyer est déjà payé (prochain dans une semaine). */
    public boolean take(long id, UUID tenant, String tenantName, long nextDue) {
        return database.execute("UPDATE " + table + " SET tenant = ?, tenant_name = ?, rent_due = ?, trusted = NULL"
                + " WHERE id = ? AND tenant IS NULL AND rent > 0", tenant, tenantName, nextDue, id) > 0;
    }

    /** Fin de location : la parcelle redevient au clan. */
    public void release(long id) {
        database.execute("UPDATE " + table + " SET tenant = NULL, tenant_name = NULL, trusted = NULL WHERE id = ?", id);
    }

    /** Un membre qui quitte le clan (ou en est exclu) : ses locations s'arrêtent. */
    public void releaseTenant(long clanId, UUID tenant) {
        database.execute("UPDATE " + table + " SET tenant = NULL, tenant_name = NULL, trusted = NULL WHERE clan_id = ? AND tenant = ?",
                clanId, tenant);
    }

    /** Réserve l'encaissement de ce loyer (false si un autre serveur l'a déjà fait). */
    public boolean claimRent(long id, long due, long nextDue) {
        return database.execute("UPDATE " + table + " SET rent_due = ? WHERE id = ? AND rent_due = ? AND tenant IS NOT NULL",
                nextDue, id, due) > 0;
    }

    public void setTrusted(long id, Set<UUID> trusted) {
        database.update(TABLE, Map.of("trusted", trusted.stream().map(UUID::toString).collect(Collectors.joining(","))),
                Map.of("id", id));
    }

    private static Zone toZone(Row row) {
        String trusted = row.getString("trusted");
        Set<UUID> players = trusted == null || trusted.isBlank() ? Set.of()
                : Arrays.stream(trusted.split(",")).map(UUID::fromString).collect(Collectors.toUnmodifiableSet());
        return new Zone(row.getLong("id"), row.getLong("clan_id"), row.getString("server"), row.getString("world"),
                row.getInt("min_x"), row.getInt("min_z"), row.getInt("max_x"), row.getInt("max_z"), row.getString("name"),
                row.getDouble("rent"), row.get("tenant") == null ? null : row.getUUID("tenant"), row.getString("tenant_name"),
                row.getLong("rent_due"), players);
    }
}
