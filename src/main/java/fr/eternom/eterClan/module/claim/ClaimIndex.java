package fr.eternom.eterClan.module.claim;

import fr.eternom.eterClan.module.claim.ClaimRepository.Claim;
import org.bukkit.Chunk;
import org.bukkit.Location;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les chunks pris sur CE serveur, en mémoire : « à qui est ce bloc ? » se répond sans la base, à chaque événement.
 * Rechargé en entier (un serveur, peu de lignes) au démarrage et quand un serveur annonce un changement.
 */
public class ClaimIndex {

    /** Un chunk d'un monde. */
    public record ChunkKey(String world, int x, int z) {

        public static ChunkKey of(Location location) {
            return new ChunkKey(location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        }

        public static ChunkKey of(Chunk chunk) {
            return new ChunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        }

        public ChunkKey relative(int dx, int dz) {
            return new ChunkKey(world, x + dx, z + dz);
        }
    }

    private volatile Map<ChunkKey, Long> owners = new ConcurrentHashMap<>();

    public void load(List<Claim> claims) {
        Map<ChunkKey, Long> next = new ConcurrentHashMap<>();
        claims.forEach(claim -> next.put(new ChunkKey(claim.world(), claim.x(), claim.z()), claim.clanId()));
        owners = next;
    }

    public void put(ChunkKey chunk, long clanId) {
        owners.put(chunk, clanId);
    }

    public void remove(ChunkKey chunk) {
        owners.remove(chunk);
    }

    public OptionalLong owner(ChunkKey chunk) {
        Long clan = owners.get(chunk);
        return clan == null ? OptionalLong.empty() : OptionalLong.of(clan);
    }

    public OptionalLong owner(Location location) {
        return owner(ChunkKey.of(location));
    }

    /** Clans qui ont un terrain sur ce serveur (à garder en mémoire). */
    public Collection<Long> clans() {
        return owners.values().stream().distinct().toList();
    }

    /** Un des 4 chunks voisins appartient à ce clan (le terrain doit rester d'un seul tenant). */
    public boolean touches(ChunkKey chunk, long clanId) {
        return List.of(chunk.relative(1, 0), chunk.relative(-1, 0), chunk.relative(0, 1), chunk.relative(0, -1)).stream()
                .anyMatch(neighbour -> owner(neighbour).orElse(-1) == clanId);
    }

    /**
     * Le territoire du clan reste d'un seul tenant sans ce chunk (4 voisins, même monde) : parcours depuis un chunk
     * restant. Un territoire réduit à ce chunk (le dernier) reste valable.
     */
    public boolean staysConnected(long clanId, ChunkKey removed) {
        Set<ChunkKey> rest = new HashSet<>();
        owners.forEach((chunk, clan) -> {
            if (clan == clanId && !chunk.equals(removed)) {
                rest.add(chunk);
            }
        });
        if (rest.isEmpty()) {
            return true;
        }
        Deque<ChunkKey> queue = new ArrayDeque<>(List.of(rest.iterator().next()));
        Set<ChunkKey> reached = new HashSet<>(queue);
        while (!queue.isEmpty()) {
            ChunkKey chunk = queue.poll();
            for (ChunkKey next : List.of(chunk.relative(1, 0), chunk.relative(-1, 0), chunk.relative(0, 1), chunk.relative(0, -1))) {
                if (rest.contains(next) && reached.add(next)) {
                    queue.add(next);
                }
            }
        }
        return reached.size() == rest.size();
    }

    /** Le clan a-t-il au moins un chunk sur ce serveur ? */
    public boolean hasLand(long clanId) {
        return owners.containsValue(clanId);
    }
}
