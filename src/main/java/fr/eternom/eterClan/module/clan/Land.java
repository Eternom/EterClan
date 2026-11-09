package fr.eternom.eterClan.module.clan;

import fr.eternom.eterClan.module.claim.LandPlan.Plot;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Le terrain d'un clan vu de partout (tous serveurs) : ses chunks, son chunk primaire (le centre du cœur), son home,
 * sa dette d'entretien, qui peut y entrer (mode, offre de pass, passes vendus ou offerts, liste noire).
 */
public record Land(Set<Plot> plots, Plot heart, Home home, double debt, long debtSince, long heartMovedAt, AccessMode mode,
                   PassOffer offer, List<Pass> passes, Set<UUID> banned) {

    public static final Land EMPTY = new Land(Set.of(), null, null, 0, 0, 0, AccessMode.OPEN, PassOffer.NONE, List.of(), Set.of());

    /** Qui entre sur le terrain : tout le monde (OPEN), personne sans accès (CLOSED), avec un pass acheté (PASS). */
    public enum AccessMode { OPEN, CLOSED, PASS;

        public static AccessMode parse(String value) {
            try {
                return value == null ? OPEN : valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return OPEN;
            }
        }

        public AccessMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** Le home du clan (/clan home), sur n'importe quel serveur. */
    public record Home(String server, String world, double x, double y, double z, float yaw, float pitch) {
    }

    /** Ce qu'un pass coûte, combien de jours il dure, ce qu'il permet (jamais construire). */
    public record PassOffer(double price, int days, Set<ClanPermission> rights) {

        /** Ce qu'un pass peut permettre : jamais construire, ni gérer le clan. */
        public static final Set<ClanPermission> ALLOWED = EnumSet.of(ClanPermission.DOORS, ClanPermission.REDSTONE,
                ClanPermission.CROPS, ClanPermission.ANIMALS, ClanPermission.CONTAINERS);
        public static final PassOffer NONE = new PassOffer(0, 7, EnumSet.of(ClanPermission.DOORS, ClanPermission.CROPS,
                ClanPermission.ANIMALS, ClanPermission.REDSTONE));

        public boolean forSale() {
            return price > 0;
        }
    }

    /**
     * Un accès donné à un joueur hors du clan : acheté (paid) ou offert (guest, par un membre). zoneId 0 = le terrain du
     * clan, sinon une parcelle.
     */
    public record Pass(long id, long zoneId, UUID player, String name, long expiresAt, double paid, boolean guest) {

        public boolean isValid(long now) {
            return expiresAt > now;
        }
    }

    public boolean hasPass(UUID player, long zoneId, long now) {
        return passes.stream().anyMatch(pass -> pass.player().equals(player) && pass.zoneId() == zoneId && pass.isValid(now));
    }

    public boolean isGuest(UUID player, long now) {
        return passes.stream().anyMatch(pass -> pass.guest() && pass.player().equals(player) && pass.isValid(now));
    }
}
