package fr.eternom.eterClan.module.menu;

import fr.eternom.eterClan.module.zone.Zone;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Qui peut construire et ouvrir dans la parcelle louée (le locataire seulement) : une tête par joueur autorisé,
 * clic = lui retirer l'accès ; bouton : ajouter un joueur (son pseudo dans une fenêtre).
 */
class TrustMenu implements Menu {

    private static final List<Integer> SLOTS = IntStream.rangeClosed(1, 4)
            .flatMap(row -> IntStream.rangeClosed(row * 9 + 1, row * 9 + 7)).boxed().toList();
    private static final int ADD = 47;
    private static final int BACK = 49;

    private final ClanGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Zone zone;
    private final Inventory inventory;
    private final Map<Integer, OfflinePlayer> trustedAt = new HashMap<>();

    TrustMenu(ClanGui gui, Player viewer, Zone zone) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.zone = zone;
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "trust.title", "zone", zone.name()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Runnable reopen = () -> gui.openTrust(player, zone.id());
        OfflinePlayer trusted = trustedAt.get(slot);
        if (trusted != null) {
            Sounds.click(player);
            gui.zones().untrust(player, zone, trusted.getUniqueId(), String.valueOf(trusted.getName()), reopen);
        } else if (slot == ADD) {
            Sounds.click(player);
            gui.askText(player, "trust", "", name -> gui.zones().trust(player, zone, name, reopen), reopen);
        } else if (slot == BACK) {
            Sounds.page(player);
            gui.openZone(player, zone.id());
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        List<UUID> players = zone.trusted().stream().toList();
        for (int i = 0; i < players.size() && i < SLOTS.size(); i++) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(players.get(i));
            String name = player.getName() == null ? "?" : player.getName();
            trustedAt.put(SLOTS.get(i), player);
            inventory.setItem(SLOTS.get(i), Items.head(Bukkit.createProfile(player.getUniqueId(), name),
                    messages.get(viewer, "members.name", "player", name), List.of(messages.get(viewer, "trust.remove"))));
        }
        inventory.setItem(ADD, Items.item(Material.WRITABLE_BOOK, messages.get(viewer, "trust.add.name"),
                List.of(messages.get(viewer, "trust.add.lore"))));
        inventory.setItem(BACK, Items.item(Material.ARROW, messages.get(viewer, "zone-menu.back-zone"), List.of()));
    }
}
