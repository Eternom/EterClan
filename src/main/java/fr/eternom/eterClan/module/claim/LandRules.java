package fr.eternom.eterClan.module.claim;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Les règles du terrain (config.yml > land), en plus des prix (Pricing) :
 * - niveaux : chaque palier atteint (membres ET chunks) donne un niveau ; le top 3 du classement du mois en donne un de
 *   plus ; les avant-postes permis suivent le niveau (outposts) ;
 * - un avant-poste : au plus outpost-size chunks ;
 * - zone tampon : pas de chunk à moins de buffer chunks du terrain d'un autre clan ;
 * - zones interdites : des rectangles en blocs (le spawn, les zones du staff) ;
 * - le cœur se déplace une fois tous les heart-move-days jours, pour heart-move-price ;
 * - dette d'entretien : debt-grace-days jours avant de perdre des chunks ;
 * - clan inactif (aucun membre venu) : chunks payants perdus après inactive-paid-days, tout le terrain après
 *   inactive-all-days (le staff est prévenu avant).
 */
public record LandRules(List<Level> levels, int outpostSize, int buffer, List<Area> forbidden, double heartMovePrice,
                        int heartMoveDays, int debtGraceDays, int inactivePaidDays, int inactiveAllDays) {

    /** Un palier : membres et chunks à atteindre, avant-postes permis à ce niveau. */
    public record Level(int members, int chunks, int outposts) {
    }

    /** Un rectangle interdit, en blocs, dans un monde. */
    public record Area(String world, int minX, int minZ, int maxX, int maxZ) {

        public boolean touchesChunk(String chunkWorld, int chunkX, int chunkZ) {
            int x0 = chunkX << 4;
            int z0 = chunkZ << 4;
            return world.equals(chunkWorld) && x0 <= maxX && x0 + 15 >= minX && z0 <= maxZ && z0 + 15 >= minZ;
        }
    }

    public static LandRules load(ConfigurationSection land) {
        List<Level> levels = new ArrayList<>();
        for (Map<?, ?> map : land.getMapList("levels")) {
            levels.add(new Level(number(map.get("members"), 1), number(map.get("chunks"), 0), number(map.get("outposts"), 1)));
        }
        if (levels.isEmpty()) {
            levels = List.of(new Level(1, 0, 1), new Level(3, 20, 2), new Level(6, 40, 3));
        }
        List<Area> forbidden = new ArrayList<>();
        for (Map<?, ?> map : land.getMapList("forbidden")) {
            int x1 = number(map.get("x1"), 0);
            int z1 = number(map.get("z1"), 0);
            int x2 = number(map.get("x2"), 0);
            int z2 = number(map.get("z2"), 0);
            forbidden.add(new Area(String.valueOf(map.get("world")), Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2)));
        }
        return new LandRules(List.copyOf(levels), Math.max(1, land.getInt("outpost-size", 4)), Math.max(0, land.getInt("buffer", 1)),
                List.copyOf(forbidden), Math.max(0, land.getDouble("heart-move-price", 5000)), Math.max(0, land.getInt("heart-move-days", 30)),
                Math.max(0, land.getInt("debt-grace-days", 14)), Math.max(0, land.getInt("inactive-paid-days", 30)),
                Math.max(0, land.getInt("inactive-all-days", 60)));
    }

    /** Le niveau du clan (1 au moins) : paliers atteints, +1 s'il est dans le top 3 du mois. */
    public int level(int members, int chunks, int leagueRank) {
        int level = 0;
        for (Level step : levels) {
            if (members >= step.members() && chunks >= step.chunks()) {
                level++;
            }
        }
        if (leagueRank >= 1 && leagueRank <= 3) {
            level++;
        }
        return Math.max(1, Math.min(level, levels.size()));
    }

    public int maxOutposts(int level) {
        return levels.get(Math.max(0, Math.min(level, levels.size()) - 1)).outposts();
    }

    /** Le palier suivant (pour l'afficher), vide au dernier niveau. */
    public java.util.Optional<Level> next(int level) {
        return level < levels.size() ? java.util.Optional.of(levels.get(level)) : java.util.Optional.empty();
    }

    public boolean forbids(String world, int chunkX, int chunkZ) {
        return forbidden.stream().anyMatch(area -> area.touchesChunk(world, chunkX, chunkZ));
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
