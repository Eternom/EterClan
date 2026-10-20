package fr.eternom.eterClan.module.menu;

import fr.eternom.eterClan.module.claim.Access;
import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanPermission;
import fr.eternom.eterClan.module.clan.ClanRule;
import fr.eternom.eterClan.module.zone.Zone;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Règles du territoire, 6 lignes : ce que le joueur peut faire, et pourquoi. Lecture seule : les permissions se règlent
 * membre par membre (Membres -> un membre -> Permissions) ; un clic sur une permission y mène qui peut les gérer.
 * <pre>
 *  ▣ ▣ ▢ ▢ ⌖ ▢ ▢ ▣ ▣     ⌖ = ici : où il se tient, et ce qu'il peut y faire (parcelle comprise)
 *  ▣ · · · · · · · ▣
 *  ▢ B C P · R K A ▢     sur le terrain du clan : construire, coffres, portes, redstone, cultures, animaux (✔ / ✘)
 *  ▣ · · · H · · · ▣     H = avoir des homes sur le terrain
 *  ▢ ⚔ ✹ ♨ ⛨ ☠ ✖ · ▢     combat, explosions, feu : protégés sauf si le clan les autorise (clic : chef et PERMISSIONS) ;
 *                        l'extérieur, créatures, visiteurs : toujours protégés
 *  ▣ ▣ ▢ ▤ ← ▢ ▢ ▣ ▣     ▤ = la règle des parcelles louées
 * </pre>
 * Une parcelle louée obéit à ses propres règles : son locataire et ceux qu'il a choisis, jamais les permissions du clan.
 */
class RulesMenu implements Menu {

    /** Les permissions de terrain, dans l'ordre affiché. */
    private static final List<ClanPermission> LAND = List.of(ClanPermission.BUILD, ClanPermission.CONTAINERS, ClanPermission.DOORS,
            ClanPermission.REDSTONE, ClanPermission.CROPS, ClanPermission.ANIMALS, ClanPermission.HOMES);
    private static final int[] LAND_SLOTS = {19, 20, 21, 23, 24, 25, 31};
    private static final String[] PROTECTIONS = {"pvp", "explosions", "fire", "outside", "mobs", "visitors"};
    private static final Material[] PROTECTION_ICONS = {Material.IRON_SWORD, Material.TNT, Material.FLINT_AND_STEEL,
            Material.PISTON, Material.ZOMBIE_HEAD, Material.BARRIER};
    private static final int[] PROTECTION_SLOTS = {37, 38, 39, 41, 42, 43};
    /** Les trois premières protections se règlent (dans l'ordre de PROTECTIONS) ; les autres sont toujours actives. */
    private static final ClanRule[] RULES = {ClanRule.PVP, ClanRule.EXPLOSIONS, ClanRule.FIRE};
    private static final int HERE = 4;
    private static final int ZONES = 48;
    private static final int BACK = 49;

    private final ClanGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Clan clan;
    private final Inventory inventory;

    RulesMenu(ClanGui gui, Player viewer, Clan clan) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.clan = clan;
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "rules.title", "clan", clan.name()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK) {
            Sounds.page(player);
            gui.open(player);
        } else if (slot == HERE) {
            Sounds.click(player);
            gui.openRules(player); // se rafraîchit : le joueur a pu bouger
        } else if (ruleAt(slot) != null && canManage()) {
            Sounds.click(player);
            gui.clans().toggleRule(player, clan, ruleAt(slot), () -> gui.openRules(player));
        } else if (isLandSlot(slot) && canManage()) {
            Sounds.page(player);
            gui.openMembers(player); // les permissions se règlent membre par membre
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        inventory.setItem(HERE, here());
        for (int i = 0; i < LAND.size(); i++) {
            ClanPermission permission = LAND.get(i);
            boolean can = clan.can(viewer.getUniqueId(), permission);
            List<Component> lore = new ArrayList<>(List.of(
                    text("rules.land." + permission.id()),
                    Component.empty(),
                    text(clan.isOwner(viewer.getUniqueId()) ? "rules.allowed-owner" : can ? "rules.allowed" : "rules.denied"),
                    text("rules.read-only")));
            if (canManage()) {
                lore.add(text("rules.manage"));
            }
            inventory.setItem(LAND_SLOTS[i], Items.item(can ? permission.icon() : Material.GRAY_DYE,
                    text("permission." + permission.id()), lore));
        }
        inventory.setItem(22, Items.item(Material.WHITE_BANNER, text("rules.land-info.name"), List.of(
                text("rules.land-info.lore"), text("rules.land-info.owner"))));
        for (int i = 0; i < PROTECTIONS.length; i++) {
            List<Component> lore = new ArrayList<>(List.of(text("rules.protection." + PROTECTIONS[i] + ".lore"), Component.empty()));
            boolean allowed = false;
            if (i < RULES.length) {
                allowed = clan.allows(RULES[i]);
                lore.add(text(allowed ? "rules.rule-on" : "rules.rule-off"));
                if (canManage()) {
                    lore.add(text(allowed ? "rules.rule-click-protect" : "rules.rule-click-allow"));
                }
            } else {
                lore.add(text("rules.always"));
            }
            inventory.setItem(PROTECTION_SLOTS[i], Items.item(allowed ? Material.GRAY_DYE : PROTECTION_ICONS[i],
                    text("rules.protection." + PROTECTIONS[i] + ".name"), lore, !allowed));
        }
        inventory.setItem(40, Items.item(Material.SHIELD, text("rules.protected.name"), List.of(text("rules.protected.lore"))));
        long rented = gui.zonesOf(clan).stream().filter(zone -> viewer.getUniqueId().equals(zone.tenant())).count();
        long trusted = gui.zonesOf(clan).stream().filter(zone -> zone.isRented() && zone.trusted().contains(viewer.getUniqueId())).count();
        inventory.setItem(ZONES, Items.item(Material.OAK_FENCE, text("rules.zones.name"), List.of(
                text("rules.zones.lore"), text("rules.zones.lore2"), Component.empty(),
                text("rules.zones.mine", "rented", String.valueOf(rented), "trusted", String.valueOf(trusted)))));
        inventory.setItem(BACK, Items.item(Material.OAK_DOOR, text("menu.back-to-clan"), List.of()));
    }

    /** Là où se tient le joueur : à qui est l'endroit, et chaque action permise ou non (parcelle louée comprise). */
    private ItemStack here() {
        Location location = viewer.getLocation();
        OptionalLong owner = gui.sync().claims().owner(location);
        List<Component> lore = new ArrayList<>();
        if (owner.isEmpty()) {
            lore.add(text("rules.here.wild"));
        } else {
            Optional<Zone> zone = gui.sync().zones().at(location)
                    .filter(found -> found.clanId() == owner.getAsLong() && found.isRented());
            String clanName = gui.sync().cache().get(owner.getAsLong()).map(found -> "[" + found.tag() + "] " + found.name()).orElse("?");
            if (zone.isPresent()) {
                lore.add(text("rules.here.zone", "zone", zone.get().name(), "tenant", String.valueOf(zone.get().tenantName())));
                lore.add(text("rules.here.zone-rule"));
            } else {
                lore.add(text(owner.getAsLong() == clan.id() ? "rules.here.own" : "rules.here.other", "clan", clanName));
            }
            lore.add(Component.empty());
            Access access = gui.access();
            for (ClanPermission permission : LAND) {
                lore.add(text(access.allows(viewer, location, permission) ? "rules.here.yes" : "rules.here.no",
                        "action", messages.plain(viewer, "permission." + permission.id())));
            }
        }
        lore.add(Component.empty());
        lore.add(text("rules.here.refresh"));
        return Items.item(Material.COMPASS, text("rules.here.name"), lore);
    }

    private boolean canManage() {
        return clan.can(viewer.getUniqueId(), ClanPermission.PERMISSIONS);
    }

    /** La règle réglable à cette case, null sinon. */
    private static ClanRule ruleAt(int slot) {
        for (int i = 0; i < RULES.length; i++) {
            if (PROTECTION_SLOTS[i] == slot) {
                return RULES[i];
            }
        }
        return null;
    }

    private static boolean isLandSlot(int slot) {
        for (int landSlot : LAND_SLOTS) {
            if (landSlot == slot) {
                return true;
            }
        }
        return false;
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
