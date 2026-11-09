package fr.eternom.eterClan.module.clan;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Le journal d'un clan (eterclan_journal), gardé 90 jours : ce qui s'est passé (CLAN : chunks pris ou rendus, invitations,
 * exclusions, permissions, home, accès...) et l'argent de la réserve (BANK : dons, retraits, entretien, passes, loyers).
 * Une ligne : une clé de lang/ (journal.<clé>) et ses valeurs, qui s'affichent dans la langue de qui lit.
 */
public class Journal {

    public enum Category { CLAN, BANK }

    /** Une ligne du journal : who a fait key (avec ses valeurs), à createdAt. */
    public record Entry(String actor, String key, List<String> values, long createdAt) {
    }

    private static final String TABLE = "journal";
    private static final String SEPARATOR = "\u001F";
    private static final long KEEP_MILLIS = 90L * 86_400_000L;

    private final JavaPlugin plugin;
    private final Database database;
    private final String table;

    public Journal(JavaPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
        database.createTable(TABLE,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("clan_id", Column.Type.LONG).notNull(),
                Column.of("category", Column.Type.STRING).length(8).notNull(),
                Column.of("actor", Column.Type.STRING).length(32).notNull(),
                Column.of("entry", Column.Type.STRING).length(48).notNull(),
                Column.of("vals", Column.Type.STRING).length(255).notNull(),
                Column.of("created_at", Column.Type.LONG).notNull());
        this.table = database.table(TABLE);
        database.execute("CREATE INDEX IF NOT EXISTS idx_clan ON " + table + " (clan_id, created_at)");
    }

    /** N'importe quel thread : écrit en tâche de fond. values : paires inutiles, seulement les valeurs dans l'ordre. */
    public void add(long clanId, Category category, String actor, String key, String... values) {
        String joined = String.join(SEPARATOR, values);
        Tasks.async(plugin, () -> database.insert(TABLE, Map.of("clan_id", clanId, "category", category.name(),
                "actor", actor == null ? "" : actor, "entry", key, "vals", joined.length() > 255 ? joined.substring(0, 255) : joined,
                "created_at", System.currentTimeMillis())), "Journal du clan non écrit");
    }

    /** Bloquant : les dernières lignes. */
    public List<Entry> last(long clanId, Category category, int limit) {
        return database.query("SELECT * FROM " + table + " WHERE clan_id = ? AND category = ? ORDER BY created_at DESC LIMIT ?",
                clanId, category.name(), limit).stream().map(row -> {
            String vals = row.getString("vals");
            return new Entry(row.getString("actor"), row.getString("entry"),
                    vals == null || vals.isEmpty() ? List.of() : Arrays.asList(vals.split(SEPARATOR, -1)), row.getLong("created_at"));
        }).toList();
    }

    public void purge() {
        database.execute("DELETE FROM " + table + " WHERE created_at < ?", System.currentTimeMillis() - KEEP_MILLIS);
    }
}
