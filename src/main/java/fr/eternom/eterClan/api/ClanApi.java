package fr.eternom.eterClan.api;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ce qu'EterClan offre aux autres plugins : le clan d'un joueur et la protection de son terrain (ex : un /rtp qui
 * évite d'arriver dans une base, une prison qui oppose les clans...). Personne d'autre ne lit les tables eterclan_* :
 * on demande ici.
 * <pre>
 *     // compileOnly("com.github.Eternom:EterClan:&lt;tag&gt;") ; plugin.yml : softdepend: [EterClan]
 * </pre>
 */
public interface ClanApi {

    /** Un clan et ses membres. */
    record ClanInfo(long id, String name, String tag, UUID owner, List<UUID> members) {
    }

    /** L'API d'EterClan si le plugin tourne sur ce serveur. */
    static Optional<ClanApi> get() {
        return Optional.ofNullable(Bukkit.getServicesManager().load(ClanApi.class));
    }

    /** Le clan d'un joueur, connecté ou non. Bloquant (base) : hors du thread principal. */
    Optional<ClanInfo> clanOf(UUID player);

    /** Ce bloc est dans le terrain d'un clan (sur ce serveur). Thread principal, sans base. */
    boolean isClaimed(Location location);

    /** Le joueur peut construire là (hors terrain : oui ; sinon selon ses permissions de clan). Thread principal. */
    boolean canBuild(Player player, Location location);

    /** Le menu /clan. Thread principal. */
    void openMenu(Player player);
}
