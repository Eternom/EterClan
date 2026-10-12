package fr.eternom.eterClan.module.clan;

import fr.eternom.eterLib.helper.ranking.RankingSource;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Classement des clans par territoire (chunks), offert aux autres plugins par EterLib (EterReward l'affiche dans
 * /classement). Une ligne porte le chef du clan ; son nom affiché est celui du clan.
 */
public class ClanRanking implements RankingSource {

    private final ClanRepository clans;
    /** Nom affiché de chaque chef de clan du dernier top : « [TAG] Nom ». */
    private final Map<UUID, String> labels = new ConcurrentHashMap<>();

    public ClanRanking(ClanRepository clans) {
        this.clans = clans;
    }

    @Override
    public String id() {
        return "clans";
    }

    @Override
    public List<Ranked> top(int limit) {
        return clans.topByChunks(limit).stream().map(rank -> {
            labels.put(rank.owner(), "[" + rank.tag() + "] " + rank.name());
            return new Ranked(rank.owner(), rank.chunks());
        }).toList();
    }

    /** Le clan du joueur : sa place et ses chunks ; {0, 0} sans clan. */
    @Override
    public long[] of(UUID player) {
        return clans.byMember(player).map(clan -> new long[]{clans.rankOfChunks(clan.chunks()), clan.chunks()})
                .orElse(new long[]{0, 0});
    }

    @Override
    public String label(Ranked entry) {
        return labels.get(entry.player());
    }

    @Override
    public Material icon(Ranked entry) {
        return Material.WHITE_BANNER;
    }
}
