package fr.eternom.eterClan.module.clan;

import fr.eternom.eterClan.module.clan.Clan.Member;
import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Clans et membres, communs à tout le réseau (eterclan_clans, eterclan_members). Tout mouvement d'argent est UNE
 * requête relative qui vérifie elle-même le solde : deux serveurs ou un double clic ne créent jamais d'argent.
 * Bloquant : hors du thread principal.
 */
public class ClanRepository {

    private static final String CLANS = "clans";
    private static final String MEMBERS = "members";
    /** Chunks des clans (tous serveurs) : comptés ici pour le prix et l'entretien ; gérés par ClaimRepository. */
    private static final String CLAIMS = "claims";
    /** Parcelles des clans : supprimées avec le clan ; gérées par ZoneRepository. */
    private static final String ZONES = "zones";

    private final Database database;

    public ClanRepository(Database database) {
        this.database = database;
        database.createTable(CLANS,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("name", Column.Type.STRING).length(32).notNull(),
                Column.of("tag", Column.Type.STRING).length(8).notNull(),
                Column.of("owner", Column.Type.UUID).notNull(),
                Column.of("reserve", Column.Type.DOUBLE).notNull(),
                Column.of("interest", Column.Type.DOUBLE).notNull(),
                Column.of("next_cycle", Column.Type.LONG).notNull(),
                Column.of("created_at", Column.Type.LONG).notNull());
        database.createTable(MEMBERS,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("clan_id", Column.Type.LONG).notNull(),
                Column.of("name", Column.Type.STRING).length(16).notNull(),
                Column.of("joined_at", Column.Type.LONG).notNull(),
                Column.of("account", Column.Type.DOUBLE).notNull(),
                Column.of("permissions", Column.Type.STRING).length(255).notNull());
    }

    // ---------- Lecture ----------

    public Optional<Clan> byId(long id) {
        return database.getFirst(CLANS, Map.of("id", id)).map(this::toClan);
    }

    public Optional<Clan> byMember(UUID player) {
        return database.getFirst(MEMBERS, Map.of("uuid", player)).flatMap(row -> byId(row.getLong("clan_id")));
    }

    /** Nom ou tag déjà pris (sans tenir compte des majuscules). */
    public boolean nameTaken(String name, String tag) {
        return !database.query("SELECT id FROM " + database.table(CLANS) + " WHERE LOWER(name) = LOWER(?) OR LOWER(tag) = LOWER(?)",
                name, tag).isEmpty();
    }

    public Optional<Clan> byName(String name) {
        return database.query("SELECT * FROM " + database.table(CLANS) + " WHERE LOWER(name) = LOWER(?) OR LOWER(tag) = LOWER(?)",
                name, name).stream().findFirst().map(this::toClan);
    }

    /** Clans dont le passage hebdomadaire est dû. */
    public List<Long> dueCycles(long now) {
        return database.query("SELECT id FROM " + database.table(CLANS) + " WHERE next_cycle <= ?", now).stream()
                .map(row -> row.getLong("id")).toList();
    }

    // ---------- Clan et membres ----------

    /** Crée le clan et y inscrit son chef ; vide si le joueur est déjà dans un clan. */
    public Optional<Long> create(String name, String tag, UUID owner, String ownerName, double interest, long now, long nextCycle) {
        if (database.getFirst(MEMBERS, Map.of("uuid", owner)).isPresent()) {
            return Optional.empty();
        }
        database.insert(CLANS, Map.of("name", name, "tag", tag, "owner", owner, "reserve", 0.0, "interest", interest,
                "next_cycle", nextCycle, "created_at", now));
        long id = database.query("SELECT id FROM " + database.table(CLANS) + " WHERE owner = ? ORDER BY id DESC", owner)
                .getFirst().getLong("id");
        addMember(id, owner, ownerName, Set.of(), now);
        return Optional.of(id);
    }

    /** false si le joueur est déjà dans un clan. */
    public boolean addMember(long clanId, UUID player, String name, Set<ClanPermission> permissions, long now) {
        return database.execute("INSERT IGNORE INTO " + database.table(MEMBERS)
                        + " (uuid, clan_id, name, joined_at, account, permissions) VALUES (?, ?, ?, ?, 0, ?)",
                player, clanId, name, now, ClanPermission.format(permissions)) > 0;
    }

    /** Retire le membre et rend ce qui était sur son compte (à lui reverser) ; vide s'il n'était pas dans ce clan. */
    public Optional<Double> removeMember(long clanId, UUID player) {
        Optional<Double> account = database.getFirst(MEMBERS, Map.of("uuid", player, "clan_id", clanId)).map(row -> row.getDouble("account"));
        if (account.isPresent() && database.delete(MEMBERS, Map.of("uuid", player, "clan_id", clanId)) > 0) {
            return account;
        }
        return Optional.empty();
    }

    public void setPermissions(UUID player, Set<ClanPermission> permissions) {
        database.update(MEMBERS, Map.of("permissions", ClanPermission.format(permissions)), Map.of("uuid", player));
    }

    public void setOwner(long clanId, UUID owner) {
        database.update(CLANS, Map.of("owner", owner), Map.of("id", clanId));
    }

    public void setInterest(long clanId, double interest) {
        database.update(CLANS, Map.of("interest", interest), Map.of("id", clanId));
    }

    /** Pseudo à jour (affiché dans les menus), à chaque connexion. */
    public void rename(UUID player, String name) {
        database.update(MEMBERS, Map.of("name", name), Map.of("uuid", player));
    }

    /** Dissout le clan (membres, chunks, parcelles) : rend ce que contient chaque compte (membre -> montant) ; la réserve est perdue. */
    public Map<UUID, Double> delete(long clanId) {
        Map<UUID, Double> refunds = new HashMap<>();
        database.get(MEMBERS, Map.of("clan_id", clanId)).forEach(row -> refunds.put(row.getUUID("uuid"), row.getDouble("account")));
        database.delete(MEMBERS, Map.of("clan_id", clanId));
        database.delete(CLAIMS, Map.of("clan_id", clanId));
        database.delete(ZONES, Map.of("clan_id", clanId));
        database.delete(CLANS, Map.of("id", clanId));
        return refunds;
    }

    // ---------- Argent ----------

    public void deposit(UUID player, double amount) {
        database.execute("UPDATE " + database.table(MEMBERS) + " SET account = account + ? WHERE uuid = ?", amount, player);
    }

    /** false si le compte n'a pas assez. */
    public boolean withdraw(UUID player, double amount) {
        return database.execute("UPDATE " + database.table(MEMBERS) + " SET account = account - ? WHERE uuid = ? AND account >= ?",
                amount, player, amount) > 0;
    }

    public void addReserve(long clanId, double amount) {
        database.execute("UPDATE " + database.table(CLANS) + " SET reserve = reserve + ? WHERE id = ?", amount, clanId);
    }

    /** false si la réserve n'a pas assez. */
    public boolean takeReserve(long clanId, double amount) {
        return database.execute("UPDATE " + database.table(CLANS) + " SET reserve = reserve - ? WHERE id = ? AND reserve >= ?",
                amount, clanId, amount) > 0;
    }

    /** Réserve le passage hebdomadaire pour ce serveur : false si un autre serveur l'a déjà pris. */
    public boolean claimCycle(long clanId, long expected, long next) {
        return database.execute("UPDATE " + database.table(CLANS) + " SET next_cycle = ? WHERE id = ? AND next_cycle = ?",
                next, clanId, expected) > 0;
    }

    private Clan toClan(Row row) {
        long id = row.getLong("id");
        List<Member> members = database.get(MEMBERS, Map.of("clan_id", id)).stream()
                .map(member -> new Member(member.getUUID("uuid"), member.getString("name"), member.getLong("joined_at"),
                        member.getDouble("account"), ClanPermission.parse(member.getString("permissions"))))
                .toList();
        int chunks = database.query("SELECT COUNT(*) AS chunks FROM " + database.table(CLAIMS) + " WHERE clan_id = ?", id)
                .stream().findFirst().map(count -> count.getInt("chunks")).orElse(0);
        return new Clan(id, row.getString("name"), row.getString("tag"), row.getUUID("owner"), row.getDouble("reserve"),
                row.getDouble("interest"), row.getLong("next_cycle"), chunks, members);
    }
}
