package fr.eternom.eterClan.module.clan;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les clans utiles à ce serveur, en mémoire : ceux qui ont un terrain ici et ceux des joueurs connectés. Les vérifications
 * de protection (à chaque bloc cassé) les lisent sans jamais toucher à la base. Remplis et relus en tâche de fond par
 * ClanService (au démarrage, à la connexion, et quand un autre serveur annonce un changement).
 */
public class ClanCache {

    private final Map<Long, Clan> clans = new ConcurrentHashMap<>();
    /** Joueur -> son clan, pour les joueurs des clans en mémoire. */
    private final Map<UUID, Long> memberOf = new ConcurrentHashMap<>();

    public Optional<Clan> get(long id) {
        return Optional.ofNullable(clans.get(id));
    }

    public Optional<Clan> of(UUID player) {
        Long id = memberOf.get(player);
        return id == null ? Optional.empty() : get(id);
    }

    public Collection<Clan> all() {
        return clans.values();
    }

    /** Remplace (ou ajoute) un clan relu en base. */
    public void put(Clan clan) {
        remove(clan.id());
        clans.put(clan.id(), clan);
        clan.members().forEach(member -> memberOf.put(member.uuid(), clan.id()));
    }

    /** Clan dissous (ou disparu de la base). */
    public void remove(long id) {
        clans.remove(id);
        memberOf.values().removeIf(clanId -> clanId == id);
    }

    /** Un joueur qui n'est plus dans aucun clan (exclu, parti) : oublié jusqu'à la relecture de son nouveau clan. */
    public void forget(UUID player) {
        memberOf.remove(player);
    }
}
