package fr.eternom.eterClan.module.claim;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Prix du terrain, payé au serveur (un évier de l'économie) : free chunks gratuits, puis chaque chunk en plus coûte
 * growth fois le précédent, jamais plus de cap fois le premier ; l'entretien hebdomadaire suit la même courbe. S'agrandir
 * un peu ne coûte presque rien, un très grand terrain coûte cher à garder (config.yml > land).
 */
public record Pricing(int free, double price, double upkeep, double growth, double cap) {

    public static Pricing load(ConfigurationSection land) {
        return new Pricing(Math.max(0, land.getInt("free-chunks", 9)), Math.max(0, land.getDouble("price", 250)),
                Math.max(0, land.getDouble("upkeep", 20)), Math.max(1, land.getDouble("growth", 1.07)),
                Math.max(1, land.getDouble("cap", 15)));
    }

    /** Prix du chunk numéro n du clan (1 = le premier) : 0 pour les chunks gratuits. */
    public double priceOf(int n) {
        return n <= free ? 0 : step(price, n - free);
    }

    /** Entretien hebdomadaire d'un clan de chunks chunks. */
    public double upkeepOf(int chunks) {
        double total = 0;
        for (int extra = 1; extra <= chunks - free; extra++) {
            total += step(upkeep, extra);
        }
        return Math.round(total);
    }

    /** Montant du extra-ième chunk payant : base × growth^(extra-1), plafonné à base × cap, arrondi à l'unité. */
    private double step(double base, int extra) {
        return Math.round(Math.min(base * Math.pow(growth, extra - 1), base * cap));
    }
}
