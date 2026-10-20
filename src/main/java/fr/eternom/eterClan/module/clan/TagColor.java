package fr.eternom.eterClan.module.clan;

import org.bukkit.Material;

import java.util.Arrays;
import java.util.Locale;

/**
 * Couleurs possibles du tag d'un clan (Tab, chat, pseudo) ; les crochets restent gris clair. Choisie par le chef et ceux
 * qui ont PERMISSIONS ; nom affiché : lang/ > tag-color.<id>. ORANGE : celle d'avant les couleurs (par défaut).
 */
public enum TagColor {

    ORANGE("#FF7A00", Material.ORANGE_DYE),
    RED("#FF5555", Material.RED_DYE),
    PINK("#FF7AC6", Material.PINK_DYE),
    MAGENTA("#E05AFF", Material.MAGENTA_DYE),
    PURPLE("#B57BFF", Material.PURPLE_DYE),
    BLUE("#5B8CFF", Material.BLUE_DYE),
    LIGHT_BLUE("#7FC8FF", Material.LIGHT_BLUE_DYE),
    CYAN("#3FD0C9", Material.CYAN_DYE),
    GREEN("#3FBF5A", Material.GREEN_DYE),
    LIME("#A6E84A", Material.LIME_DYE),
    YELLOW("#FFE14D", Material.YELLOW_DYE),
    WHITE("#FFFFFF", Material.WHITE_DYE);

    private final String hex;
    private final Material icon;

    TagColor(String hex, Material icon) {
        this.hex = hex;
        this.icon = icon;
    }

    public String hex() {
        return hex;
    }

    public Material icon() {
        return icon;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Le tag dans sa couleur, en MiniMessage (le tag ne contient que des lettres et des chiffres). */
    public String paint(String tag) {
        return "<" + hex + ">" + tag + "</" + hex + ">";
    }

    /** Couleur enregistrée ; inconnue ou absente : ORANGE. */
    public static TagColor parse(String id) {
        return Arrays.stream(values()).filter(color -> color.id().equalsIgnoreCase(id)).findFirst().orElse(ORANGE);
    }
}
