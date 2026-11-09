package fr.eternom.eterClan.module.claim;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/**
 * Prix du terrain, payé au serveur (un évier de l'économie), config.yml > land :
 * - le cœur (3 × 3 autour du chunk primaire) est gratuit et sans entretien ;
 * - une extension coûte price × growth^(n-1) (n : son rang parmi les extensions), jamais plus de cap × price ;
 * - un chunk d'avant-poste coûte outpost-factor fois plus, à l'achat comme à l'entretien ;
 * - l'entretien hebdomadaire suit une courbe plus douce : upkeep × upkeep-growth^(n-1), plafonnée de même ;
 * - rendre un chunk payant rembourse refund (25 %) de ce qu'il a coûté ;
 * - le top 3 du classement du mois paie moins d'entretien (league-discount, en %).
 */
public record Pricing(double price, double growth, double cap, double upkeep, double upkeepGrowth, double outpostFactor,
                      double refund, List<Integer> leagueDiscount) {

    public static Pricing load(ConfigurationSection land) {
        List<Integer> discount = land.getIntegerList("league-discount");
        return new Pricing(Math.max(0, land.getDouble("price", 250)), Math.max(1, land.getDouble("growth", 1.07)),
                Math.max(1, land.getDouble("cap", 15)), Math.max(0, land.getDouble("upkeep", 20)),
                Math.max(1, land.getDouble("upkeep-growth", 1.04)), Math.max(1, land.getDouble("outpost-factor", 1.5)),
                Math.min(1, Math.max(0, land.getDouble("refund", 0.25))), discount.isEmpty() ? List.of(30, 20, 10) : discount);
    }

    /** Prix de la n-ième extension (1 = la première). */
    public double extensionPrice(int n) {
        return step(price, growth, n);
    }

    /** Prix d'un chunk d'avant-poste, quand le clan en a déjà outposts. */
    public double outpostPrice(int outposts) {
        return Math.round(step(price, growth, outposts + 1) * outpostFactor);
    }

    /** Entretien hebdomadaire : extensions sur la courbe, chunks d'avant-postes chacun au prix fort ; rank : place au classement (0 = hors podium). */
    public double upkeepOf(int extensions, int outposts, int rank) {
        double total = 0;
        for (int n = 1; n <= extensions; n++) {
            total += step(upkeep, upkeepGrowth, n);
        }
        for (int n = 1; n <= outposts; n++) {
            total += step(upkeep, upkeepGrowth, n) * outpostFactor;
        }
        double discount = rank >= 1 && rank <= leagueDiscount.size() ? leagueDiscount.get(rank - 1) / 100.0 : 0;
        return Math.round(total * (1 - discount));
    }

    /** Ce que rapporte un chunk rendu qui avait coûté paid. */
    public double refundOf(double paid) {
        return Math.floor(paid * refund);
    }

    /** base × factor^(n-1), plafonné à base × cap, arrondi à l'unité. */
    private double step(double base, double factor, int n) {
        return Math.round(Math.min(base * Math.pow(factor, Math.max(0, n - 1)), base * cap));
    }
}
