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
        bus.on(CLAN, data -> reloadClan(data.get("id").getAsLong()));
        bus.on(CLAIMS, data -> reloadClaims());
        bus.on(ZONES, data -> reloadZones());
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

    /** Après une modification du clan (membres, argent, permissions) : relu ici, puis sur les autres serveurs. */
    public void clanChanged(long id) {
        reloadClan(id);
        JsonObject data = new JsonObject();
        data.addProperty("id", id);
        bus.publish(CLAN, data);
    }

    public void claimsChanged() {
        reloadClaims();
        bus.publish(CLAIMS, new JsonObject());
    }

    public void zonesChanged() {
        reloadZones();
        bus.publish(ZONES, new JsonObject());
    }

    private void reloadClan(long id) {
        Tasks.async(plugin, () -> {
            Optional<Clan> clan = clans.byId(id);
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Les anciens membres oubliés, puis le clan tel qu'il est maintenant (absent : dissous)
                cache.get(id).ifPresent(old -> old.members().forEach(member -> cache.forget(member.uuid())));
                clan.ifPresentOrElse(cache::put, () -> cache.remove(id));
                Bukkit.getOnlinePlayers().forEach(this::showTag);
            });
        }, "Clan " + id + " non relu");
    }

    private void reloadClaims() {
        Tasks.async(plugin, () -> {
            claimIndex.load(claims.onServer(server));
            // Un clan qui vient de poser son premier chunk ici doit être en mémoire
            claimIndex.clans().stream().filter(id -> cache.get(id).isEmpty())
                    .forEach(id -> clans.byId(id).ifPresent(clan -> Bukkit.getScheduler().runTask(plugin, () -> cache.put(clan))));
        }, "Chunks des clans non relus");
    }

    private void reloadZones() {
        Tasks.async(plugin, () -> zoneIndex.load(zones.onServer(server)), "Parcelles non relues");
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
