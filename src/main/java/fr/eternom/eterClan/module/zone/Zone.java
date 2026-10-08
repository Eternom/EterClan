package fr.eternom.eterClan.module.zone;

import org.bukkit.Location;

import java.util.Set;
import java.util.UUID;

/**
 * Une parcelle (sous-claim) dans le terrain d'un clan : un rectangle au bloc près, sur toute la hauteur du monde.
 * rent : loyer par semaine (0 = pas à louer). Louée (tenant), elle n'obéit plus aux permissions du clan : seuls son
 * locataire et ceux qu'il a choisis (trusted) y construisent et ouvrent les coffres. rentDue : date du prochain loyer.
 */
public record Zone(long id, long clanId, String server, String world, int minX, int minZ, int maxX, int maxZ,
                   String name, double rent, UUID tenant, String tenantName, long rentDue, Set<UUID> trusted) {

    public boolean contains(Location location) {
        return location.getWorld().getName().equals(world)
                && location.getBlockX() >= minX && location.getBlockX() <= maxX
                && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
    }

    public boolean overlaps(String otherWorld, int otherMinX, int otherMinZ, int otherMaxX, int otherMaxZ) {
        return world.equals(otherWorld) && minX <= otherMaxX && maxX >= otherMinX && minZ <= otherMaxZ && maxZ >= otherMinZ;
    }

    /** Touche le chunk (x, z) (une parcelle dont un chunk est perdu disparaît). */
    public boolean overlapsChunk(String chunkWorld, int chunkX, int chunkZ) {
        return overlaps(chunkWorld, chunkX << 4, chunkZ << 4, (chunkX << 4) + 15, (chunkZ << 4) + 15);
    }

    public boolean isRented() {
        return tenant != null;
    }

    /** Le locataire, ou un joueur qu'il a autorisé. */
    public boolean allows(UUID player) {
        return player.equals(tenant) || trusted.contains(player);
    }

    public int area() {
        return (maxX - minX + 1) * (maxZ - minZ + 1);
    }
}
