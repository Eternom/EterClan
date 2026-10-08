package fr.eternom.eterClan.module.menu;

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

import java.util.List;

/**
 * /clan pour un joueur sans clan, 3 lignes : ce qu'est un clan, le créer (fenêtre avec nom, tag et prix affiché avant
 * de payer), accepter une invitation.
 */
class NoClanMenu implements Menu {

    private static final int INFO = 11;
    private static final int CREATE = 13;
    private static final int ACCEPT = 15;
    private static final int BACK = 22;

    private final ClanGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Inventory inventory;

    NoClanMenu(ClanGui gui, Player viewer) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, 27, messages.get(viewer, "no-clan.title"));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        switch (slot) {
            case CREATE -> {
                Sounds.click(player);
                gui.askCreate(player, "", "");
            }
            case ACCEPT -> {
                Sounds.click(player);
                player.closeInventory();
                gui.clans().accept(player);
            }
            case BACK -> gui.back().click(player);
            default -> {
            }
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        inventory.setItem(INFO, Items.item(Material.BOOK, messages.get(viewer, "no-clan.info.name"), List.of(
                messages.get(viewer, "no-clan.info.line1"), messages.get(viewer, "no-clan.info.line2"),
                messages.get(viewer, "no-clan.info.line3"), messages.get(viewer, "no-clan.info.line4"))));
        inventory.setItem(CREATE, Items.item(Material.WHITE_BANNER, messages.get(viewer, "no-clan.create.name"), List.of(
                messages.get(viewer, "no-clan.create.price", "price", Money.format(gui.clans().creationPrice())),
                messages.get(viewer, "no-clan.create.click")), true));
        inventory.setItem(ACCEPT, Items.item(Material.WRITABLE_BOOK, messages.get(viewer, "no-clan.accept.name"),
                List.of(messages.get(viewer, "no-clan.accept.lore"))));
        inventory.setItem(BACK, gui.back().item(viewer));
    }
}
