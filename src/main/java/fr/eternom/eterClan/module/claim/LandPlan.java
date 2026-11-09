package fr.eternom.eterClan.module.claim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * La forme du terrain d'un clan, sans Bukkit ni base (testable) :
 * - le CŒUR : le carré de 3 × 3 chunks autour du chunk primaire (heart), gratuit et sans entretien ;
 * - les EXTENSIONS : les chunks reliés au cœur (4 voisins, même serveur et monde), payants ;
 * - les AVANT-POSTES : des groupes de chunks posés ailleurs, sans lien avec la base (au plus maxOutposts groupes de
 *   maxOutpostSize chunks), plus chers.
 * Prendre un chunk : dans le carré du cœur, contre la base, contre un avant-poste (s'il n'est pas plein), ou un nouvel
 * avant-poste. Rendre un chunk : jamais un chunk du cœur (on rend tout le terrain d'un coup), jamais en coupant la base ou
 * un avant-poste en deux. Faute d'entretien : les avant-postes partent d'abord, puis les extensions les plus loin du cœur,
 * sans jamais couper la base ; le cœur ne se perd jamais.
 */
public final class LandPlan {

    /** Un chunk d'un clan, sur un serveur et dans un monde. */
    public record Plot(String server, String world, int x, int z) {

        public Plot relative(int dx, int dz) {
            return new Plot(server, world, x + dx, z + dz);
        }

        boolean sameWorld(Plot other) {
            return server.equals(other.server) && world.equals(other.world);
        }

        List<Plot> neighbours() {
            return List.of(relative(1, 0), relative(-1, 0), relative(0, 1), relative(0, -1));
        }

        int distanceTo(Plot other) {
            return sameWorld(other) ? Math.max(Math.abs(x - other.x), Math.abs(z - other.z)) : Integer.MAX_VALUE / 2;
        }
    }

    public enum Kind { CORE, EXTENSION, OUTPOST }

    /** Ce que donnerait la prise d'un chunk : sa sorte, ou le refus (clé de message). */
    public record Decision(Kind kind, String refusal) {

        static Decision ok(Kind kind) {
            return new Decision(kind, null);
        }

        static Decision no(String refusal) {
            return new Decision(null, refusal);
        }

        public boolean allowed() {
            return refusal == null;
        }
    }

    private LandPlan() {
    }

    /** Dans le carré 3 × 3 du cœur. */
    public static boolean isCore(Plot heart, Plot plot) {
        return heart != null && plot.sameWorld(heart) && Math.abs(plot.x() - heart.x()) <= 1 && Math.abs(plot.z() - heart.z()) <= 1;
    }

    /** Les 9 chunks du cœur. */
    public static List<Plot> coreSquare(Plot heart) {
        List<Plot> square = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                square.add(heart.relative(dx, dz));
            }
        }
        return square;
    }

    /** La base : les chunks du clan reliés au chunk primaire (vide si le cœur n'est pas à lui). */
    public static Set<Plot> base(Plot heart, Set<Plot> owned) {
        return heart == null || !owned.contains(heart) ? Set.of() : component(heart, owned);
    }

    public static Kind kindOf(Plot heart, Set<Plot> owned, Plot plot) {
        if (isCore(heart, plot)) {
            return Kind.CORE;
        }
        return base(heart, owned).contains(plot) ? Kind.EXTENSION : Kind.OUTPOST;
    }

    /** Les avant-postes : les groupes de chunks hors de la base. */
    public static List<Set<Plot>> outposts(Plot heart, Set<Plot> owned) {
        Set<Plot> base = base(heart, owned);
        Set<Plot> seen = new HashSet<>(base);
        List<Set<Plot>> groups = new ArrayList<>();
        for (Plot plot : owned) {
            if (seen.add(plot)) {
                Set<Plot> group = component(plot, owned);
                seen.addAll(group);
                groups.add(group);
            }
        }
        return groups;
    }

    /** Les chunks payants : le nombre d'extensions et de chunks d'avant-postes. */
    public static int[] paidCounts(Plot heart, Set<Plot> owned) {
        int extensions = 0;
        int outposts = 0;
        Set<Plot> base = base(heart, owned);
        for (Plot plot : owned) {
            if (isCore(heart, plot)) {
                continue;
            }
            if (base.contains(plot)) {
                extensions++;
            } else {
                outposts++;
            }
        }
        return new int[]{extensions, outposts};
    }

    /** Prendre ce chunk (libre) : où il irait, ou pourquoi c'est refusé. */
    public static Decision claim(Plot heart, Set<Plot> owned, Plot plot, int maxOutposts, int maxOutpostSize) {
        if (owned.contains(plot)) {
            return Decision.no("claim.already-yours");
        }
        if (heart == null || owned.isEmpty()) {
            return Decision.ok(Kind.CORE); // le premier chunk : il devient le cœur
        }
        if (isCore(heart, plot)) {
            return Decision.ok(Kind.CORE);
        }
        Set<Plot> base = base(heart, owned);
        if (plot.neighbours().stream().anyMatch(base::contains)) {
            return Decision.ok(Kind.EXTENSION);
        }
        List<Set<Plot>> outposts = outposts(heart, owned);
        List<Set<Plot>> touching = outposts.stream().filter(group -> plot.neighbours().stream().anyMatch(group::contains)).toList();
        if (touching.size() > 1) {
            return Decision.no("claim.outposts-merge"); // relierait deux avant-postes en un seul trop grand
        }
        if (touching.size() == 1) {
            return touching.getFirst().size() >= maxOutpostSize ? Decision.no("claim.outpost-full") : Decision.ok(Kind.OUTPOST);
        }
        if (outposts.size() >= maxOutposts) {
            return Decision.no("claim.outposts-max");
        }
        return Decision.ok(Kind.OUTPOST);
    }

    /** Rendre ce chunk : vide si c'est possible, sinon la clé du refus. */
    public static Optional<String> unclaim(Plot heart, Set<Plot> owned, Plot plot) {
        if (!owned.contains(plot)) {
            return Optional.of("claim.not-yours");
        }
        if (isCore(heart, plot) && owned.size() > 1) {
            return Optional.of("claim.core-locked");
        }
        Set<Plot> rest = new HashSet<>(owned);
        rest.remove(plot);
        // La base et chaque avant-poste restent d'un seul tenant
        int before = groups(owned);
        int after = groups(rest);
        boolean wasAlone = plot.neighbours().stream().noneMatch(owned::contains);
        if (after > before - (wasAlone ? 1 : 0)) {
            return Optional.of("claim.would-split");
        }
        return Optional.empty();
    }

    /**
     * Faute d'entretien : les chunks à perdre, dans l'ordre (count au plus) : les avant-postes (les plus loin du cœur
     * d'abord), puis les extensions les plus loin du cœur sans couper la base. Le cœur n'est jamais perdu.
     */
    public static List<Plot> lossOrder(Plot heart, Set<Plot> owned, int count) {
        List<Plot> lost = new ArrayList<>();
        Set<Plot> rest = new HashSet<>(owned);
        Set<Plot> base = base(heart, owned);
        List<Plot> outposts = rest.stream().filter(plot -> !base.contains(plot) && !isCore(heart, plot))
                .sorted(Comparator.comparingInt((Plot plot) -> heart == null ? 0 : plot.distanceTo(heart)).reversed()).toList();
        for (Plot plot : outposts) {
            if (lost.size() >= count) {
                return lost;
            }
            lost.add(plot);
            rest.remove(plot);
        }
        while (lost.size() < count) {
            Optional<Plot> next = rest.stream()
                    .filter(plot -> !isCore(heart, plot))
                    .filter(plot -> {
                        Set<Plot> without = new HashSet<>(rest);
                        without.remove(plot);
                        return base(heart, without).size() == without.size();
                    })
                    .max(Comparator.comparingInt((Plot plot) -> plot.distanceTo(heart))
                            .thenComparingInt(plot -> Math.abs(plot.x()) + Math.abs(plot.z())));
            if (next.isEmpty()) {
                break;
            }
            lost.add(next.get());
            rest.remove(next.get());
        }
        return lost;
    }

    /** Nombre de groupes de chunks (4 voisins, même serveur et monde). */
    static int groups(Set<Plot> owned) {
        Set<Plot> seen = new HashSet<>();
        int count = 0;
        for (Plot plot : owned) {
            if (seen.add(plot)) {
                seen.addAll(component(plot, owned));
                count++;
            }
        }
        return count;
    }

    private static Set<Plot> component(Plot start, Set<Plot> owned) {
        Set<Plot> reached = new HashSet<>(List.of(start));
        Deque<Plot> queue = new ArrayDeque<>(reached);
        while (!queue.isEmpty()) {
            for (Plot next : queue.poll().neighbours()) {
                if (owned.contains(next) && reached.add(next)) {
                    queue.add(next);
                }
            }
        }
        return reached;
    }
}
