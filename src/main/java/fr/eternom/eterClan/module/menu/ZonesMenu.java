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
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Les parcelles du clan sur ce serveur (28 au plus) : vertes = la sienne (louée par soi), rouges = louées par un autre,
 * dorées = à louer, blanches = au clan. Clic : la parcelle. Bouton : nouvelle parcelle (ZONES), tracée dans le monde.
 */
class ZonesMenu implements Menu {

    private static final List<Integer> SLOTS = IntStream.rangeClosed(1, 4)
            .flatMap(row -> IntStream.rangeClosed(row * 9 + 1, row * 9 + 7)).boxed().toList();
    private static final int NEW = 47;
    private static final int BACK = 49;

    private final ClanGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Clan clan;
    private final Inventory inventory;
    private final Map<Integer, Long> zoneAt = new HashMap<>();

    ZonesMenu(ClanGui gui, Player viewer, Clan clan) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.clan = clan;
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "zones.title", "clan", clan.name()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Long zone = zoneAt.get(slot);
        if (zone != null) {
            Sounds.page(player);
            gui.openZone(player, zone);
        } else if (slot == NEW) {
            if (!clan.can(player.getUniqueId(), ClanPermission.ZONES)) {
                messages.send(player, "clan.no-permission");
                return;
            }
            Sounds.click(player);
            gui.newZone(player);
        } else if (slot == BACK) {
            Sounds.page(player);
            gui.open(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        List<Zone> zones = gui.zonesOf(clan).stream().sorted(Comparator.comparing(Zone::name)).toList();
        for (int i = 0; i < zones.size() && i < SLOTS.size(); i++) {
            Zone zone = zones.get(i);
            zoneAt.put(SLOTS.get(i), zone.id());
            inventory.setItem(SLOTS.get(i), Items.item(icon(zone), messages.get(viewer, "zones.name", "zone", zone.name()), lore(zone)));
        }
        if (zones.isEmpty()) {
            inventory.setItem(22, Items.item(Material.MAP, messages.get(viewer, "zones.empty.name"),
                    List.of(messages.get(viewer, "zones.empty.lore"))));
        }
        boolean can = clan.can(viewer.getUniqueId(), ClanPermission.ZONES);
        inventory.setItem(NEW, Items.item(can ? Material.GOLDEN_SHOVEL : Material.GRAY_DYE, messages.get(viewer, "zones.new.name"),
                List.of(messages.get(viewer, can ? "zones.new.lore" : "menu.locked"))));
        inventory.setItem(BACK, Items.item(Material.ARROW, messages.get(viewer, "menu.back-to-clan"), List.of()));
    }

    private Material icon(Zone zone) {
        if (zone.isRented()) {
            return viewer.getUniqueId().equals(zone.tenant()) ? Material.LIME_BANNER : Material.RED_BANNER;
        }
        return zone.rent() > 0 ? Material.YELLOW_BANNER : Material.WHITE_BANNER;
    }

    private List<Component> lore(Zone zone) {
        List<Component> lore = new ArrayList<>();
        lore.add(messages.get(viewer, "zones.area", "area", String.valueOf(zone.area())));
        if (zone.isRented()) {
            lore.add(messages.get(viewer, "zones.rented", "tenant", zone.tenantName(), "rent", Money.format(zone.rent())));
        } else if (zone.rent() > 0) {
            lore.add(messages.get(viewer, "zones.for-rent", "rent", Money.format(zone.rent())));
        } else {
            lore.add(messages.get(viewer, "zones.free"));
        }
        lore.add(messages.get(viewer, "zones.click"));
        return lore;
    }
}
