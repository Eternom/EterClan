package fr.eternom.eterClan.module.clan;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ce qu'un clan peut AUTORISER sur son terrain (protégé par défaut) : combat entre joueurs, explosions, feu. Réglé par
 * le chef et ceux qui ont PERMISSIONS (menu Règles du territoire). En base : « pvp,fire » (colonne rules des clans).
 */
public enum ClanRule {

    PVP, EXPLOSIONS, FIRE;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** "pvp,fire" -> {PVP, FIRE} ; un nom inconnu est ignoré. */
    public static Set<ClanRule> parse(String text) {
        Set<ClanRule> rules = EnumSet.noneOf(ClanRule.class);
        if (text == null || text.isBlank()) {
            return rules;
        }
        for (String id : text.split(",")) {
            Arrays.stream(values()).filter(rule -> rule.id().equals(id.trim())).findFirst().ifPresent(rules::add);
        }
        return rules;
    }

    public static String format(Set<ClanRule> rules) {
        return rules.stream().map(ClanRule::id).collect(Collectors.joining(","));
    }
}
