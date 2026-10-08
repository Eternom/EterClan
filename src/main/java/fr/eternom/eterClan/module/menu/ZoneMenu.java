package fr.eternom.eterClan.module.menu;

import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanPermission;
import fr.eternom.eterClan.module.zone.Zone;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Une parcelle, 3 lignes : ses infos, puis les actions possibles pour CE joueur.
 * Locataire : accès (qui peut construire), quitter la location. Membre : la louer (si elle est à louer).
 * ZONES : fixer le loyer, mettre fin à la location, supprimer. Tout ce qui coûte ou détruit demande confirmation.
 */
class ZoneMenu implements Menu {

    private static final int INFO = 4;
    private static final List<Integer> ACTIONS = List.of(11, 12, 13, 14, 15);
    private static final int BACK = 22;

    private final ClanGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Clan clan;
    private final Zone zone;
    private final Inventory inventory;
    private final Map<Integer, Runnable> actionAt = new HashMap<>();

    ZoneMenu(ClanGui gui, Player viewer, Clan clan, Zone zone) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.clan = clan;
        this.zone = zone;
        this.inventory = Bukkit.createInventory(this, 27, messages.get(viewer, "zone-menu.title", "zone", zone.name()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Runnable action = actionAt.get(slot);
        if (action != null) {
            Sounds.click(player);
            action.run();
        } else if (slot == BACK) {
            Sounds.page(player);
            gui.openZones(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        String status = zone.isRented() ? messages.plain(viewer, "zone-menu.status.rented", "tenant", zone.tenantName())
                : zone.rent() > 0 ? messages.plain(viewer, "zone-menu.status.for-rent") : messages.plain(viewer, "zone-menu.status.free");
        inventory.setItem(INFO, Items.item(Material.OAK_SIGN, messages.get(viewer, "zones.name", "zone", zone.name()), List.of(
                messages.get(viewer, "zones.area", "area", String.valueOf(zone.area())),
                messages.get(viewer, "zone-menu.status.line", "status", status),
                messages.get(viewer, "zone-menu.rent", "rent", Money.format(zone.rent())))));

        Runnable reopen = () -> gui.openZone(viewer, zone.id());
        boolean tenant = viewer.getUniqueId().equals(zone.tenant());
        boolean manager = clan.can(viewer.getUniqueId(), ClanPermission.ZONES);
        int next = 0;
        if (tenant) {
            next = add(next, Material.PLAYER_HEAD, "zone-menu.access", () -> gui.openTrust(viewer, zone.id()));
            next = add(next, Material.IRON_DOOR, "zone-menu.leave", () -> gui.confirm(viewer, "zone-leave",
                    () -> gui.zones().end(viewer, zone, () -> gui.openZones(viewer)), reopen, "zone", zone.name()));
        } else if (!zone.isRented() && zone.rent() > 0) {
            next = add(next, Material.EMERALD, "zone-menu.take", () -> gui.confirm(viewer, "zone-take",
                    () -> gui.zones().take(viewer, zone, reopen), reopen, "zone", zone.name(), "rent", Money.format(zone.rent())));
        }
        if (manager && !zone.isRented()) {
            next = add(next, Material.GOLD_INGOT, "zone-menu.set-rent", () -> gui.askAmount(viewer, "rent",
                    rent -> gui.zones().setRent(viewer, zone, rent, reopen), reopen));
            next = add(next, Material.LAVA_BUCKET, "zone-menu.delete", () -> gui.confirm(viewer, "zone-delete",
                    () -> gui.zones().delete(viewer, zone, () -> gui.openZones(viewer)), reopen, "zone", zone.name()));
        }
        if (manager && zone.isRented() && !tenant) {
            add(next, Material.SHEARS, "zone-menu.evict", () -> gui.confirm(viewer, "zone-evict",
                    () -> gui.zones().end(viewer, zone, reopen), reopen, "zone", zone.name(), "tenant", zone.tenantName()));
        }
        inventory.setItem(BACK, Items.item(Material.ARROW, messages.get(viewer, "zone-menu.back"), List.of()));
    }

    /** Pose le bouton key à la prochaine case libre ; renvoie l'index suivant. */
    private int add(int index, Material icon, String key, Runnable action) {
        int slot = ACTIONS.get(index);
        inventory.setItem(slot, Items.item(icon, messages.get(viewer, key + ".name"), List.of(messages.get(viewer, key + ".lore"))));
        actionAt.put(slot, action);
        return index + 1;
    }
}
