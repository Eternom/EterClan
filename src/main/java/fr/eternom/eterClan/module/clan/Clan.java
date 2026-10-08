package fr.eternom.eterClan.module.clan;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Un clan et ses membres, tel que lu en base. reserve : la caisse commune ; interest : pourcentage pris chaque semaine
 * sur le compte de chaque membre et versé à la réserve ; nextCycle : date du prochain passage hebdomadaire (intérêt,
 * entretien). chunks : nombre de chunks du clan sur tout le réseau.
 */
public record Clan(long id, String name, String tag, UUID owner, double reserve, double interest, long nextCycle,
                   int chunks, List<Member> members) {

    /**
     * Un membre : son compte en banque (protégé de la mort), ses permissions, et showRank : il affiche son grade plutôt
     * que le tag du clan (Tab, chat, pseudo).
     */
    public record Member(UUID uuid, String name, long joinedAt, double account, Set<ClanPermission> permissions,
                         boolean showRank) {
    }

    public Optional<Member> member(UUID player) {
        return members.stream().filter(member -> member.uuid().equals(player)).findFirst();
    }

    public boolean isOwner(UUID player) {
        return owner.equals(player);
    }

    /** Le chef a toutes les permissions. */
    public boolean can(UUID player, ClanPermission permission) {
        return isOwner(player) || member(player).map(member -> member.permissions().contains(permission)).orElse(false);
    }

    public Set<ClanPermission> permissionsOf(UUID player) {
        if (isOwner(player)) {
            return EnumSet.allOf(ClanPermission.class);
        }
        return member(player).map(Member::permissions).orElse(Set.of());
    }

    public double accounts() {
        return members.stream().mapToDouble(Member::account).sum();
    }
}
