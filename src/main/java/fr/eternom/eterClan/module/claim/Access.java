package fr.eternom.eterClan.module.claim;

import fr.eternom.eterClan.module.clan.ClanPermission;
import fr.eternom.eterClan.module.sync.ClanSync;
import fr.eternom.eterClan.module.zone.Zone;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Qui peut agir où (thread principal, tout en mémoire). Hors terrain : tout le monde. Dans une parcelle louée :
 * seulement son locataire et ceux qu'il a choisis, quelles que soient leurs permissions dans le clan. Ailleurs sur le
 * terrain : les membres qui ont la permission. Le staff (eterclan.bypass.claims) passe partout.
 */
public class Access {

    public static final String BYPASS = "eterclan.bypass.claims";

    private final ClanSync sync;

    public Access(ClanSync sync) {
        this.sync = sync;
    }

    public boolean allows(Player player, Location location, ClanPermission permission) {
        OptionalLong owner = sync.claims().owner(location);
        if (owner.isEmpty() || player.hasPermission(BYPASS)) {
            return true;
        }
        Optional<Zone> zone = sync.zones().at(location).filter(found -> found.clanId() == owner.getAsLong() && found.isRented());
        if (zone.isPresent()) {
            return zone.get().allows(player.getUniqueId());
        }
        return sync.cache().get(owner.getAsLong()).map(clan -> clan.can(player.getUniqueId(), permission)).orElse(false);
    }

    /** Terrain d'un clan (n'importe lequel) : hors d'atteinte de l'extérieur. */
    public boolean isClaimed(Location location) {
        return sync.claims().owner(location).isPresent();
    }

    /** Les deux endroits n'ont pas le même propriétaire (nature sauvage = aucun). */
    public boolean differentOwners(Location from, Location to) {
        return sync.claims().owner(from).orElse(-1) != sync.claims().owner(to).orElse(-1);
    }
}
