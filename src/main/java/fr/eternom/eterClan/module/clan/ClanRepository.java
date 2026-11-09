package fr.eternom.eterClan.module.clan;

import fr.eternom.eterClan.module.clan.Clan.Member;
import fr.eternom.eterClan.module.claim.LandPlan.Plot;
import fr.eternom.eterClan.module.clan.Land.Home;
import fr.eternom.eterClan.module.clan.Land.Pass;
import fr.eternom.eterClan.module.clan.Land.PassOffer;
import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.util.HashMap;
import java.util.HashSet;
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
    /** Changements de données déjà faits (une ligne par changement) : chacun une seule fois, même avec plusieurs serveurs. */
    private static final String UPGRADES = "upgrades";
    /** Accès au terrain vendus ou offerts à des joueurs hors du clan (1.7.0). */
    private static final String PASSES = "passes";
    /** Joueurs refusés sur le terrain d'un clan (1.7.0). */
    private static final String BANNED = "banned";

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
                Column.of("permissions", Column.Type.STRING).length(255).notNull(),
                Column.of("show_rank", Column.Type.BOOLEAN));
        // 1.5.0 : ce que le clan autorise sur son terrain (vide : tout est protégé)
        database.addColumn(CLANS, Column.of("rules", Column.Type.STRING).length(64));
        // 1.6.0 : la couleur du tag (vide : orange)
        database.addColumn(CLANS, Column.of("tag_color", Column.Type.STRING).length(16));
        // 1.7.0 : le terrain (chunk primaire, home, dette, accès)
        database.addColumn(CLANS, Column.of("heart_server", Column.Type.STRING).length(64));
        database.addColumn(CLANS, Column.of("heart_world", Column.Type.STRING).length(64));
        database.addColumn(CLANS, Column.of("heart_x", Column.Type.INT));
        database.addColumn(CLANS, Column.of("heart_z", Column.Type.INT));
        database.addColumn(CLANS, Column.of("heart_moved_at", Column.Type.LONG));
        database.addColumn(CLANS, Column.of("home_server", Column.Type.STRING).length(64));
        database.addColumn(CLANS, Column.of("home_world", Column.Type.STRING).length(64));
        database.addColumn(CLANS, Column.of("home_x", Column.Type.DOUBLE));
        database.addColumn(CLANS, Column.of("home_y", Column.Type.DOUBLE));
        database.addColumn(CLANS, Column.of("home_z", Column.Type.DOUBLE));
        database.addColumn(CLANS, Column.of("home_yaw", Column.Type.DOUBLE));
        database.addColumn(CLANS, Column.of("home_pitch", Column.Type.DOUBLE));
        database.addColumn(CLANS, Column.of("debt", Column.Type.DOUBLE));
        database.addColumn(CLANS, Column.of("debt_since", Column.Type.LONG));
        database.addColumn(CLANS, Column.of("land_mode", Column.Type.STRING).length(8));
        database.addColumn(CLANS, Column.of("pass_price", Column.Type.DOUBLE));
        database.addColumn(CLANS, Column.of("pass_days", Column.Type.INT));
        database.addColumn(CLANS, Column.of("pass_rights", Column.Type.STRING).length(128));
        database.createTable(PASSES,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("clan_id", Column.Type.LONG).notNull(),
                Column.of("zone_id", Column.Type.LONG).notNull(),
                Column.of("player", Column.Type.UUID).notNull(),
                Column.of("name", Column.Type.STRING).length(16).notNull(),
                Column.of("expires_at", Column.Type.LONG).notNull(),
                Column.of("paid", Column.Type.DOUBLE).notNull(),
                Column.of("guest", Column.Type.BOOLEAN).notNull());
        database.createTable(BANNED,
                Column.of("clan_id", Column.Type.LONG).primaryKey(),
                Column.of("player", Column.Type.UUID).primaryKey(),
                Column.of("name", Column.Type.STRING).length(16).notNull());
        database.createTable(UPGRADES, Column.of("name", Column.Type.STRING).length(64).primaryKey());
        // 1.7.0 : le chunk primaire des clans qui ont déjà du terrain : leur chunk le plus ancien
        if (database.execute("INSERT IGNORE INTO " + database.table(UPGRADES) + " (name) VALUES ('heart')") > 0) {
            for (Row claim : database.query("SELECT c.clan_id, c.server, c.world, c.x, c.z FROM " + database.table(CLAIMS) + " c"
                    + " JOIN (SELECT clan_id, MIN(claimed_at) AS first FROM " + database.table(CLAIMS) + " GROUP BY clan_id) f"
                    + " ON f.clan_id = c.clan_id AND f.first = c.claimed_at")) {
                database.execute("UPDATE " + database.table(CLANS) + " SET heart_server = ?, heart_world = ?, heart_x = ?, heart_z = ?"
                                + " WHERE id = ? AND heart_server IS NULL", claim.getString("server"), claim.getString("world"),
                        claim.getInt("x"), claim.getInt("z"), claim.getLong("clan_id"));
            }
        }
        // 1.4.0 : la permission « homes » donnée aux membres déjà là (avant elle, tout membre pouvait poser un home)
        if (database.execute("INSERT IGNORE INTO " + database.table(UPGRADES) + " (name) VALUES ('homes')") > 0) {
            database.execute("UPDATE " + database.table(MEMBERS) + " SET permissions = IF(permissions = '', 'homes',"
                    + " CONCAT(permissions, ',homes')) WHERE FIND_IN_SET('homes', permissions) = 0");
        }
    }

    // ---------- Lecture ----------

    /** Identifiants de tous les clans (classement du mois). */
    public List<Long> ids() {
        return database.query("SELECT id FROM " + database.table(CLANS)).stream().map(row -> row.getLong("id")).toList();
    }

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

    /** Afficher son grade (true) ou le tag du clan (false) à la place du grade. */
    public void setShowRank(UUID player, boolean showRank) {
        database.update(MEMBERS, Map.of("show_rank", showRank), Map.of("uuid", player));
    }

    public void setOwner(long clanId, UUID owner) {
        database.update(CLANS, Map.of("owner", owner), Map.of("id", clanId));
    }

    public void setTagColor(long clanId, TagColor color) {
        database.update(CLANS, Map.of("tag_color", color.id()), Map.of("id", clanId));
    }

    public void setRules(long clanId, Set<ClanRule> rules) {
        database.update(CLANS, Map.of("rules", ClanRule.format(rules)), Map.of("id", clanId));
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
        database.delete(PASSES, Map.of("clan_id", clanId));
        database.delete(BANNED, Map.of("clan_id", clanId));
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
                        member.getDouble("account"), ClanPermission.parse(member.getString("permissions")),
                        member.getBoolean("show_rank")))
                .toList();
        Set<Plot> plots = new HashSet<>();
        database.get(CLAIMS, Map.of("clan_id", id)).forEach(claim -> plots.add(new Plot(claim.getString("server"),
                claim.getString("world"), claim.getInt("x"), claim.getInt("z"))));
        return new Clan(id, row.getString("name"), row.getString("tag"), row.getUUID("owner"), row.getDouble("reserve"),
                row.getDouble("interest"), row.getLong("next_cycle"), plots.size(), members, ClanRule.parse(row.getString("rules")),
                TagColor.parse(row.getString("tag_color")), land(id, row, Set.copyOf(plots)));
    }

    private Land land(long id, Row row, Set<Plot> plots) {
        String heartServer = row.getString("heart_server");
        Plot heart = heartServer == null ? null : new Plot(heartServer, row.getString("heart_world"), row.getInt("heart_x"),
                row.getInt("heart_z"));
        String homeServer = row.getString("home_server");
        Home home = homeServer == null ? null : new Home(homeServer, row.getString("home_world"), row.getDouble("home_x"),
                row.getDouble("home_y"), row.getDouble("home_z"), (float) row.getDouble("home_yaw"), (float) row.getDouble("home_pitch"));
        String rights = row.getString("pass_rights");
        Set<ClanPermission> passRights = rights == null ? PassOffer.NONE.rights() : ClanPermission.parse(rights);
        passRights.retainAll(PassOffer.ALLOWED);
        int days = row.getInt("pass_days");
        PassOffer offer = new PassOffer(row.getDouble("pass_price"), days <= 0 ? 7 : days, passRights);
        long now = System.currentTimeMillis();
        List<Pass> passes = database.query("SELECT * FROM " + database.table(PASSES) + " WHERE clan_id = ? AND expires_at > ?", id, now)
                .stream().map(pass -> new Pass(pass.getLong("id"), pass.getLong("zone_id"), pass.getUUID("player"),
                        pass.getString("name"), pass.getLong("expires_at"), pass.getDouble("paid"), pass.getBoolean("guest"))).toList();
        Set<UUID> banned = new HashSet<>();
        database.get(BANNED, Map.of("clan_id", id)).forEach(ban -> banned.add(ban.getUUID("player")));
        return new Land(plots, heart, home, row.getDouble("debt"), row.getLong("debt_since"), row.getLong("heart_moved_at"),
                Land.AccessMode.parse(row.getString("land_mode")), offer, passes, Set.copyOf(banned));
    }

    // ---------- Terrain (1.7.0) ----------

    public void setHeart(long clanId, Plot heart, boolean moved) {
        Map<String, Object> values = new HashMap<>(Map.of("heart_server", heart.server(), "heart_world", heart.world(),
                "heart_x", heart.x(), "heart_z", heart.z()));
        if (moved) {
            values.put("heart_moved_at", System.currentTimeMillis());
        }
        database.update(CLANS, values, Map.of("id", clanId));
    }

    /** Plus de terrain : plus de cœur ni de home (le prochain chunk pris devient le cœur). */
    public void clearHeart(long clanId) {
        database.execute("UPDATE " + database.table(CLANS) + " SET heart_server = NULL, heart_world = NULL, heart_x = NULL,"
                + " heart_z = NULL, home_server = NULL WHERE id = ?", clanId);
    }

    public void setHome(long clanId, Home home) {
        database.update(CLANS, Map.of("home_server", home.server(), "home_world", home.world(), "home_x", home.x(),
                "home_y", home.y(), "home_z", home.z(), "home_yaw", (double) home.yaw(), "home_pitch", (double) home.pitch()),
                Map.of("id", clanId));
    }

    public void setDebt(long clanId, double debt, long since) {
        database.update(CLANS, Map.of("debt", debt, "debt_since", since), Map.of("id", clanId));
    }

    public void setAccess(long clanId, Land.AccessMode mode, PassOffer offer) {
        database.update(CLANS, Map.of("land_mode", mode.name(), "pass_price", offer.price(), "pass_days", offer.days(),
                "pass_rights", ClanPermission.format(offer.rights())), Map.of("id", clanId));
    }

    public void addPass(long clanId, long zoneId, UUID player, String name, long expiresAt, double paid, boolean guest) {
        // Un nouveau pass du même joueur pour le même endroit remplace l'ancien (prolongé)
        database.execute("DELETE FROM " + database.table(PASSES) + " WHERE clan_id = ? AND zone_id = ? AND player = ?",
                clanId, zoneId, player);
        database.insert(PASSES, Map.of("clan_id", clanId, "zone_id", zoneId, "player", player, "name", name,
                "expires_at", expiresAt, "paid", paid, "guest", guest));
    }

    /** Le pass retiré ; vide s'il n'existait plus. */
    public Optional<Pass> removePass(long passId) {
        Optional<Pass> pass = database.getFirst(PASSES, Map.of("id", passId)).map(row -> new Pass(row.getLong("id"),
                row.getLong("zone_id"), row.getUUID("player"), row.getString("name"), row.getLong("expires_at"),
                row.getDouble("paid"), row.getBoolean("guest")));
        return pass.isPresent() && database.delete(PASSES, Map.of("id", passId)) > 0 ? pass : Optional.empty();
    }

    public void purgePasses(long now) {
        database.execute("DELETE FROM " + database.table(PASSES) + " WHERE expires_at <= ?", now);
    }

    /** Ajoute à la liste noire, ou retire ; true s'il y est maintenant. */
    public boolean toggleBanned(long clanId, UUID player, String name) {
        if (database.delete(BANNED, Map.of("clan_id", clanId, "player", player)) > 0) {
            return false;
        }
        database.insert(BANNED, Map.of("clan_id", clanId, "player", player, "name", name));
        return true;
    }

    public List<String> bannedNames(long clanId) {
        return database.get(BANNED, Map.of("clan_id", clanId)).stream().map(row -> row.getString("name")).sorted().toList();
    }
}
