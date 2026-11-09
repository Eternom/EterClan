package fr.eternom.eterClan.module.claim;

import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanPermission;
import fr.eternom.eterClan.module.clan.ClanRule;
import fr.eternom.eterClan.module.clan.Land;
import fr.eternom.eterClan.module.sync.ClanSync;
import fr.eternom.eterClan.module.zone.Zone;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Qui peut agir et entrer où (thread principal, tout en mémoire). Hors terrain : tout le monde. Le staff
 * (eterclan.bypass.claims) passe partout. La liste noire d'un clan n'agit ni n'entre nulle part sur son terrain.
 *
 * Agir (allows) : dans une parcelle louée, seulement son locataire et ceux qu'il a choisis ; dans une parcelle
 * réservée, seulement ceux qu'elle a choisis (et le chef) ; ailleurs, les membres qui ont la permission, et les joueurs
 * hors du clan qui ont un pass (ou sont invités) pour ce que l'offre de pass permet (jamais construire).
 *
 * Entrer (canEnter) : le terrain suit le mode du clan (ouvert, fermé, pass) ; une parcelle fermée ou à pass a le sien.
 * Membres, invités et détenteurs d'un pass du clan entrent sur le terrain ; une parcelle à pass demande son propre pass.
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
        Clan clan = sync.cache().get(owner.getAsLong()).orElse(null);
        if (clan == null) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        Land land = clan.land();
        if (land.banned().contains(uuid)) {
            return false;
        }
        Optional<Zone> zone = sync.zones().at(location).filter(found -> found.clanId() == clan.id());
        if (zone.isPresent()) {
            Zone found = zone.get();
            if (found.isRented()) {
                return found.allows(uuid);
            }
            if (found.mode() == Zone.Mode.RESERVED) {
                return clan.isOwner(uuid) || found.trusted().contains(uuid);
            }
        }
        if (clan.isMember(uuid)) {
            return clan.can(uuid, permission);
        }
        long now = System.currentTimeMillis();
        boolean hasPass = land.isGuest(uuid, now) || land.hasPass(uuid, 0, now)
                || zone.filter(found -> found.mode() == Zone.Mode.PASS).map(found -> land.hasPass(uuid, found.id(), now)).orElse(false);
        return hasPass && land.offer().rights().contains(permission);
    }

    /** Peut-il entrer à cet endroit (marcher dedans) ? */
    public boolean canEnter(Player player, Location location) {
        return refusal(player, location).isEmpty();
    }

    /**
     * Pourquoi il ne peut pas entrer ici (clé de message), vide s'il peut. « pass » : un pass s'achète pour entrer
     * (le terrain, ou la parcelle si c'est elle qui le demande).
     */
    public Optional<String> refusal(Player player, Location location) {
        OptionalLong owner = sync.claims().owner(location);
        if (owner.isEmpty() || player.hasPermission(BYPASS)) {
            return Optional.empty();
        }
        Clan clan = sync.cache().get(owner.getAsLong()).orElse(null);
        if (clan == null) {
            return Optional.empty();
        }
        UUID uuid = player.getUniqueId();
        Land land = clan.land();
        if (land.banned().contains(uuid)) {
            return Optional.of("access.banned");
        }
        long now = System.currentTimeMillis();
        boolean member = clan.isMember(uuid);
        Optional<Zone> zone = sync.zones().at(location).filter(found -> found.clanId() == clan.id());
        if (zone.isPresent()) {
            Zone found = zone.get();
            boolean inside = member || found.allows(uuid) || land.isGuest(uuid, now);
            if (found.mode() == Zone.Mode.CLOSED && !inside) {
                return Optional.of("access.closed-zone");
            }
            if (found.mode() == Zone.Mode.PASS && !inside && !land.hasPass(uuid, found.id(), now)) {
                return Optional.of("access.pass-zone");
            }
            if (found.mode() == Zone.Mode.CLOSED || found.mode() == Zone.Mode.PASS || found.allows(uuid)) {
                return Optional.empty();
            }
        }
        if (member || land.isGuest(uuid, now) || land.mode() == Land.AccessMode.OPEN) {
            return Optional.empty();
        }
        boolean anyPass = land.passes().stream().anyMatch(pass -> pass.player().equals(uuid) && pass.isValid(now));
        if (land.mode() == Land.AccessMode.PASS && anyPass) {
            return Optional.empty();
        }
        return Optional.of(land.mode() == Land.AccessMode.PASS ? "access.pass-land" : "access.closed-land");
    }

    /** Terrain d'un clan qui n'autorise pas cette règle (combat, explosions, feu) : protégé. Nature sauvage : non. */
    public boolean protects(Location location, ClanRule rule) {
        OptionalLong owner = sync.claims().owner(location);
        return owner.isPresent() && !sync.cache().get(owner.getAsLong()).map(clan -> clan.allows(rule)).orElse(false);
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
