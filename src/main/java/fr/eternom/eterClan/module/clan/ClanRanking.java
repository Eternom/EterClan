package fr.eternom.eterClan.module.clan;

import fr.eternom.eterClan.module.ranking.ClanLeague;
import fr.eternom.eterLib.helper.ranking.RankingSource;
import org.bukkit.Material;

import java.util.List;
import java.util.UUID;

/**
 * Le classement des clans du mois (ClanLeague : activité, travail, trésor, expansion), offert aux autres plugins par
 * EterLib : EterReward l'affiche dans /classement. Une ligne porte le chef du clan ; son nom affiché est celui du clan.
 * Valeur : le score en dixièmes (875 = 87,5 points).
 */
public class ClanRanking implements RankingSource {

    private final ClanLeague league;
    private final ClanRepository clans;

    public ClanRanking(ClanLeague league, ClanRepository clans) {
        this.league = league;
        this.clans = clans;
    }

    @Override
    public String id() {
        return "clans";
    }

    @Override
    public List<Ranked> top(int limit) {
        return league.current().stream().limit(limit)
                .map(named -> new Ranked(named.owner(), named.standing().score())).toList();
    }

    /** Le clan du joueur : sa place et son score du mois ; {0, 0} sans clan ou pas encore classé. */
    @Override
    public long[] of(UUID player) {
        return clans.byMember(player).flatMap(clan -> league.of(clan.id()))
                .map(named -> new long[]{named.standing().place(), named.standing().score()})
                .orElse(new long[]{0, 0});
    }

    @Override
    public String label(Ranked entry) {
        return league.current().stream().filter(named -> named.owner().equals(entry.player())).findFirst()
                .map(named -> "[" + named.tag() + "] " + named.name()).orElse(null);
    }

    @Override
    public Material icon(Ranked entry) {
        return Material.WHITE_BANNER;
    }
}
