package fr.eternom.eterClan.module.zone;

import org.bukkit.Location;

import java.util.List;
import java.util.Optional;

/** Les parcelles de CE serveur, en mémoire (vérifications de protection). Rechargées en entier à chaque changement. */
public class ZoneIndex {

    private volatile List<Zone> zones = List.of();

    public void load(List<Zone> zones) {
        this.zones = List.copyOf(zones);
    }

    public Optional<Zone> at(Location location) {
        return zones.stream().filter(zone -> zone.contains(location)).findFirst();
    }

    public List<Zone> ofClan(long clanId) {
        return zones.stream().filter(zone -> zone.clanId() == clanId).toList();
    }

    public Optional<Zone> byId(long id) {
        return zones.stream().filter(zone -> zone.id() == id).findFirst();
    }

    public Optional<Zone> byName(long clanId, String name) {
        return ofClan(clanId).stream().filter(zone -> zone.name().equalsIgnoreCase(name)).findFirst();
    }

    public boolean overlaps(String world, int minX, int minZ, int maxX, int maxZ) {
        return zones.stream().anyMatch(zone -> zone.overlaps(world, minX, minZ, maxX, maxZ));
    }
}
