package fr.eternom.eterClan.module.claim;

import fr.eternom.eterClan.module.claim.ClaimIndex.ChunkKey;
import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.sync.ClanSync;
import fr.eternom.eterClan.module.zone.Zone;
import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Où l'on est : en changeant de terrain ou de parcelle, un message dans l'action bar (« Terrain de X », « Parcelle Y
 * de Z », « Nature sauvage »). Ne regarde que les déplacements qui changent de bloc. Connexion : le clan du joueur
 * passe en mémoire (ClanSync).
 */
public class LandListener implements Listener {

    private final ClanSync sync;
    private final Messages messages;
    /** Dernier endroit annoncé à chaque joueur (clan:parcelle), pour ne parler que quand il change. */
    private final Map<UUID, String> last = new ConcurrentHashMap<>();

    public LandListener(ClanSync sync, Messages messages) {
        this.sync = sync;
        this.messages = messages;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        sync.join(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        last.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() && event.getFrom().getBlockZ() == event.getTo().getBlockZ()
                && event.getFrom().getWorld() == event.getTo().getWorld()) {
            return;
        }
        Player player = event.getPlayer();
        long owner = sync.claims().owner(ChunkKey.of(event.getTo())).orElse(-1);
        Zone zone = owner < 0 ? null : sync.zones().at(event.getTo()).filter(found -> found.clanId() == owner).orElse(null);
        String place = owner + ":" + (zone == null ? "" : zone.id());
        String previous = last.put(player.getUniqueId(), place);
        if (place.equals(previous) || (previous == null && owner < 0)) {
            return; // rien de neuf, ou premier pas en nature sauvage après la connexion
        }
        if (owner < 0) {
            messages.actionBar(player, "land.wild");
            return;
        }
        String clan = sync.cache().get(owner).map(Clan::name).orElse("?");
        if (zone == null) {
            messages.actionBar(player, "land.clan", "clan", clan);
        } else if (zone.isRented()) {
            messages.actionBar(player, "land.zone-rented", "zone", zone.name(), "tenant", zone.tenantName(), "clan", clan);
        } else {
            messages.actionBar(player, "land.zone", "zone", zone.name(), "clan", clan);
        }
    }
}
