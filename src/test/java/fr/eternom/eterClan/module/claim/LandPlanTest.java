package fr.eternom.eterClan.module.claim;

import fr.eternom.eterClan.module.claim.LandPlan.Kind;
import fr.eternom.eterClan.module.claim.LandPlan.Plot;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** La forme du terrain : cœur, extensions, avant-postes, rendre sans couper, ordre de perte, prix. */
class LandPlanTest {

    private static final Plot HEART = new Plot("survie", "world", 0, 0);

    private static Set<Plot> core() {
        return new HashSet<>(LandPlan.coreSquare(HEART));
    }

    @Test
    void firstChunkIsTheHeart() {
        assertEquals(Kind.CORE, LandPlan.claim(null, Set.of(), new Plot("survie", "world", 50, 50), 1, 4).kind());
    }

    @Test
    void kinds() {
        Set<Plot> owned = core();
        assertEquals(Kind.CORE, LandPlan.claim(HEART, Set.of(HEART), HEART.relative(1, 1), 1, 4).kind());
        assertEquals(Kind.EXTENSION, LandPlan.claim(HEART, owned, HEART.relative(2, 0), 1, 4).kind());
        assertEquals(Kind.OUTPOST, LandPlan.claim(HEART, owned, HEART.relative(10, 10), 1, 4).kind());
        // Un chunk du carré du cœur reste gratuit même si le cœur n'est pas complet
        assertEquals(Kind.CORE, LandPlan.claim(HEART, Set.of(HEART), HEART.relative(-1, 0), 1, 4).kind());
    }

    @Test
    void outpostLimits() {
        Set<Plot> owned = core();
        owned.add(HEART.relative(10, 10));
        // Un seul avant-poste permis : un second, ailleurs, est refusé
        assertEquals("claim.outposts-max", LandPlan.claim(HEART, owned, HEART.relative(-20, -20), 1, 4).refusal());
        // Grandir l'avant-poste, jusqu'à sa taille
        assertEquals(Kind.OUTPOST, LandPlan.claim(HEART, owned, HEART.relative(11, 10), 1, 2).kind());
        owned.add(HEART.relative(11, 10));
        assertEquals("claim.outpost-full", LandPlan.claim(HEART, owned, HEART.relative(12, 10), 1, 2).refusal());
        assertEquals(1, LandPlan.outposts(HEART, owned).size());
        assertEquals(2, LandPlan.paidCounts(HEART, owned)[1]);
        assertEquals(0, LandPlan.paidCounts(HEART, owned)[0]);
    }

    @Test
    void otherWorldOrServerIsAnOutpost() {
        Set<Plot> owned = core();
        Plot elsewhere = new Plot("survie", "world_nether", 1, 0);
        assertEquals(Kind.OUTPOST, LandPlan.claim(HEART, owned, elsewhere, 1, 4).kind());
    }

    @Test
    void unclaimRules() {
        Set<Plot> owned = core();
        Plot a = HEART.relative(2, 0);
        Plot b = HEART.relative(3, 0);
        owned.add(a);
        owned.add(b);
        assertEquals(Optional.of("claim.core-locked"), LandPlan.unclaim(HEART, owned, HEART.relative(1, 0)));
        assertEquals(Optional.of("claim.would-split"), LandPlan.unclaim(HEART, owned, a));
        assertTrue(LandPlan.unclaim(HEART, owned, b).isEmpty());
        // Le tout dernier chunk se rend
        assertTrue(LandPlan.unclaim(HEART, Set.of(HEART), HEART).isEmpty());
        // Un avant-poste d'un seul chunk se rend
        owned.add(HEART.relative(20, 20));
        assertTrue(LandPlan.unclaim(HEART, owned, HEART.relative(20, 20)).isEmpty());
    }

    @Test
    void lossKeepsTheCoreAndTheBase() {
        Set<Plot> owned = core();
        Plot near = HEART.relative(2, 0);
        Plot far = HEART.relative(3, 0);
        Plot outpost = HEART.relative(30, 30);
        owned.add(near);
        owned.add(far);
        owned.add(outpost);
        List<Plot> lost = LandPlan.lossOrder(HEART, owned, 3);
        assertEquals(List.of(outpost, far, near), lost);
        // Jamais le cœur, même si on en demande plus
        List<Plot> all = LandPlan.lossOrder(HEART, owned, 50);
        assertEquals(3, all.size());
        assertFalse(all.stream().anyMatch(plot -> LandPlan.isCore(HEART, plot)));
    }

    @Test
    void lossNeverSplitsTheBase() {
        Set<Plot> owned = core();
        // Un pont de 2 chunks vers un bout de 2 chunks : le bout part avant le pont
        Plot bridge1 = HEART.relative(2, 0);
        Plot bridge2 = HEART.relative(3, 0);
        Plot tip1 = HEART.relative(4, 0);
        Plot tip2 = HEART.relative(4, 1);
        owned.addAll(List.of(bridge1, bridge2, tip1, tip2));
        List<Plot> lost = LandPlan.lossOrder(HEART, owned, 2);
        assertTrue(lost.contains(tip1) || lost.contains(tip2));
        assertFalse(lost.contains(bridge1));
    }

    @Test
    void prices() {
        Pricing pricing = new Pricing(250, 1.07, 15, 20, 1.04, 1.5, 0.25, List.of(30, 20, 10));
        assertEquals(250, pricing.extensionPrice(1));
        assertEquals(268, pricing.extensionPrice(2));
        assertEquals(375, pricing.outpostPrice(0));
        assertEquals(0, pricing.upkeepOf(0, 0, 0));
        assertEquals(20 + 21, pricing.upkeepOf(2, 0, 0));
        assertEquals(30, pricing.upkeepOf(0, 1, 0));
        // Top 1 : 30 % de moins
        assertEquals(Math.round(41 * 0.7), pricing.upkeepOf(2, 0, 1));
        assertEquals(62, pricing.refundOf(250));
        // Plafond : jamais plus de cap fois le prix de base
        assertEquals(250 * 15, pricing.extensionPrice(500));
    }

    @Test
    void levels() {
        LandRules rules = new LandRules(List.of(new LandRules.Level(1, 0, 1), new LandRules.Level(3, 20, 2),
                new LandRules.Level(6, 40, 3)), 4, 1, List.of(), 5000, 30, 14, 30, 60);
        assertEquals(1, rules.level(1, 0, 0));
        assertEquals(2, rules.level(3, 25, 0));
        assertEquals(3, rules.level(3, 25, 2)); // top 3 : un niveau de plus
        assertEquals(3, rules.maxOutposts(3));
        assertTrue(new LandRules.Area("world", -100, -100, 100, 100).touchesChunk("world", 0, 0));
        assertFalse(new LandRules.Area("world", -100, -100, 100, 100).touchesChunk("world", 10, 10));
    }
}
