package fr.eternom.eterClan.module.clan;

import org.bukkit.Material;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ce qu'un membre a le droit de faire dans son clan (pas de rôles : une liste par membre, réglée par ceux qui ont
 * PERMISSIONS ; le chef a tout). Les permissions de terrain valent sur le terrain du clan, jamais dans une parcelle
 * louée (seul son locataire y décide). Nom affiché : lang/ > permission.<id>.
 */
public enum ClanPermission {

    // Terrain
    BUILD(Material.BRICKS),
    CONTAINERS(Material.CHEST),
    DOORS(Material.OAK_DOOR),
    REDSTONE(Material.LEVER),
    CROPS(Material.WHEAT),
    ANIMALS(Material.LEAD),
    // Gestion
    INVITE(Material.WRITABLE_BOOK),
    KICK(Material.IRON_DOOR),
    CLAIM(Material.GRASS_BLOCK),
    ZONES(Material.GOLDEN_HOE),
    // Argent et réglages
    RESERVE(Material.GOLD_BLOCK),
    INTEREST(Material.GOLD_NUGGET),
    PERMISSIONS(Material.NAME_TAG);

    private final Material icon;

    ClanPermission(Material icon) {
        this.icon = icon;
    }

    public Material icon() {
        return icon;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** "build,doors" -> {BUILD, DOORS} ; un nom inconnu (permission retirée) est ignoré. */
    public static Set<ClanPermission> parse(String text) {
        Set<ClanPermission> permissions = EnumSet.noneOf(ClanPermission.class);
        if (text == null || text.isBlank()) {
            return permissions;
        }
        for (String id : text.split(",")) {
            Arrays.stream(values()).filter(permission -> permission.id().equals(id.trim())).findFirst().ifPresent(permissions::add);
        }
        return permissions;
    }

    public static String format(Set<ClanPermission> permissions) {
        return permissions.stream().map(ClanPermission::id).collect(Collectors.joining(","));
    }
}
