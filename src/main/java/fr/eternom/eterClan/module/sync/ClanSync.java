package fr.eternom.eterClan.module.sync;

import com.google.gson.JsonObject;
import fr.eternom.eterClan.module.claim.ClaimIndex;
import fr.eternom.eterClan.module.claim.ClaimRepository;
import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanCache;
import fr.eternom.eterClan.module.clan.ClanRepository;
import fr.eternom.eterClan.module.zone.ZoneIndex;
import fr.eternom.eterClan.module.zone.ZoneRepository;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.tag.PlayerTags;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.UUID;

/**
 * Ce que ce serveur garde en mémoire (clans, chunks, parcelles) et sa mise à jour : relu en base après chaque
 * changement, puis annoncé aux autres serveurs (bus réseau d'EterLib, canal eterclan) qui relisent à leur tour.
 * Les relectures se font en tâche de fond ; les index sont remplacés d'un coup (lus sans verrou par les protections).
 */
public class ClanSync {

    private static final String CLAN = "clan";
    private static final String CLAIMS = "claims";
    private static final String ZONES = "zones";
    /** Étiquette du clan pour la sidebar et le Tab (EterTab : <tag_clan>). */
    private static final String TAG = "clan";
    /** Étiquette qui remplace le grade (EterTab, EterChat) : le tag du clan mis en forme, ou rien. */
    private static final String BADGE = "badge";
    /** Le staff garde toujours son grade. */
    public static final String STAFF = "eter.display.staff";

    private final JavaPlugin plugin;
    private final Messages messages;
    private final NetworkBus bus;
    private final String server;
    private final ClanRepository clans;
    private final ClaimRepository claims;
    private final ZoneRepository zones;
    private final ClanCache cache = new ClanCache();
    private final ClaimIndex claimIndex = new ClaimIndex();
    private final ZoneIndex zoneIndex = new ZoneIndex();

    public ClanSync(JavaPlugin plugin, Messages messages, NetworkBus bus, String server, ClanRepository clans,
                    ClaimRepository claims, ZoneRepository zones) {
        this.plugin = plugin;
        this.messages = messages;
        this.bus = bus;
        this.server = server;
        this.clans = clans;
        this.claims = claims;
        this.zones = zones;
    }

    /** Démarrage (bloquant, avant l'arrivée des joueurs) : chunks, parcelles et clans de ce serveur. */
    public void start() {
        claimIndex.load(claims.onServer(server));
        zoneIndex.load(zones.onServer(server));
        claimIndex.clans().forEach(id -> clans.byId(id).ifPresent(cache::put));
        // Annonces des autres serveurs (reçues sur le thread principal) : relues en tâche de fond
        bus.on(CLAN, data -> {
            long id = data.get("id").getAsLong();
            Tasks.async(plugin, () -> loadClan(id), "Clan " + id + " non relu");
        });
        bus.on(CLAIMS, data -> Tasks.async(plugin, this::loadClaims, "Chunks des clans non relus"));
        bus.on(ZONES, data -> Tasks.async(plugin, () -> zoneIndex.load(zones.onServer(server)), "Parcelles non relues"));
        Bukkit.getOnlinePlayers().forEach(this::join); // /reload
    }

    /** Connexion : le clan du joueur en mémoire, son pseudo à jour, et son étiquette. */
    public void join(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Tasks.async(plugin, player, () -> {
            clans.rename(uuid, name);
            return clans.byMember(uuid);
        }, found -> {
            found.ifPresent(cache::put);
            showTag(player);
        }, () -> { });
    }

    // ---------- Changements ----------

    /**
     * Après une modification du clan (membres, argent, permissions) : relu ici, puis annoncé aux autres serveurs.
     * Bloquant, depuis une tâche de fond : la mémoire est à jour avant la réponse au joueur (menu rouvert à jour).
     */
    public void clanChanged(long id) {
        loadClan(id);
        JsonObject data = new JsonObject();
        data.addProperty("id", id);
        bus.publish(CLAN, data);
    }

    /** Bloquant, depuis une tâche de fond (voir clanChanged). */
    public void claimsChanged() {
        loadClaims();
        bus.publish(CLAIMS, new JsonObject());
    }

    /** Bloquant, depuis une tâche de fond (voir clanChanged). */
    public void zonesChanged() {
        zoneIndex.load(zones.onServer(server));
        bus.publish(ZONES, new JsonObject());
    }

    /** Bloquant : relit le clan, puis met la mémoire à jour sur le thread principal. */
    private void loadClan(long id) {
        Optional<Clan> clan = clans.byId(id);
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Les anciens membres oubliés, puis le clan tel qu'il est maintenant (absent : dissous)
            cache.get(id).ifPresent(old -> old.members().forEach(member -> cache.forget(member.uuid())));
            clan.ifPresentOrElse(cache::put, () -> cache.remove(id));
            Bukkit.getOnlinePlayers().forEach(this::showTag);
        });
    }

    /** Bloquant : les chunks de ce serveur, et les clans qui viennent d'y poser leur premier chunk. */
    private void loadClaims() {
        claimIndex.load(claims.onServer(server));
        claimIndex.clans().stream().filter(id -> cache.get(id).isEmpty())
                .forEach(id -> clans.byId(id).ifPresent(clan -> Bukkit.getScheduler().runTask(plugin, () -> cache.put(clan))));
    }

    /**
     * Étiquettes du joueur : clan (son tag) et badge (le tag mis en forme, qui remplace le grade), sauf pour le staff
     * et pour qui préfère son grade.
     */
    public void showTag(Player player) {
        PlayerTags tags = EterLib.get().getPlayerTags();
        Optional<Clan> clan = cache.of(player.getUniqueId());
        if (clan.isEmpty()) {
            tags.remove(player, TAG);
            tags.remove(player, BADGE);
            return;
        }
        tags.set(player, TAG, clan.get().tag());
        boolean showRank = player.hasPermission(STAFF)
                || clan.get().member(player.getUniqueId()).map(Clan.Member::showRank).orElse(false);
        String format = messages.raw(player, "badge");
        if (showRank || format == null) {
            tags.remove(player, BADGE);
        } else {
            tags.set(player, BADGE, format.replace("<tag>", clan.get().tag()));
        }
    }

    // ---------- Accès ----------

    public ClanCache cache() {
        return cache;
    }

    public ClaimIndex claims() {
        return claimIndex;
    }

    public ZoneIndex zones() {
        return zoneIndex;
    }

    public String server() {
        return server;
    }

    public NetworkBus bus() {
        return bus;
    }
}
