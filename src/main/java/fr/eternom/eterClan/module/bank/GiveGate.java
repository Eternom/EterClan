package fr.eternom.eterClan.module.bank;

import fr.eternom.eterLib.helper.ranking.RankingSource;

import java.util.UUID;

/**
 * Donner à la réserve d'un clan seulement après assez de jeu (bank.min-give-playtime-hours, temps de jeu actif de
 * /stats d'EterSuccess, source playtime_total) : un second compte entré dans le clan ne peut pas y verser ses
 * récompenses pour que le compte principal les reprenne. Le compte de chaque membre n'est pas concerné (il reste à
 * lui). Sans EterSuccess : pas de limite.
 */
public final class GiveGate {

    private final long minMinutes;

    public GiveGate(double minHours) {
        this.minMinutes = Math.round(Math.max(0, minHours) * 60);
    }

    public long minHours() {
        return minMinutes / 60;
    }

    /** Minutes de jeu encore nécessaires (0 : permis). Bloquant (base). */
    public long minutesLeft(UUID player) {
        // Lu par EterLib (source playtime_total d'EterSuccess) : EterSuccess dépend déjà de ce plugin, pas l'inverse
        var source = RankingSource.find("playtime_total");
        if (minMinutes <= 0 || source.isEmpty()) {
            return 0;
        }
        long played = source.get().of(player)[1];
        return Math.max(0, minMinutes - played);
    }
}
