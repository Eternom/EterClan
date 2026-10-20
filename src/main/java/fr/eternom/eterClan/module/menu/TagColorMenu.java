package fr.eternom.eterClan.module.menu;

import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.TagColor;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.List;

/**
 * Couleur du tag du clan, 4 lignes : les 12 couleurs (aperçu du tag tel qu'il s'affichera), la couleur actuelle brille.
 * Ouvert depuis la bannière du menu du clan, par le chef et ceux qui ont PERMISSIONS.
 */
class TagColorMenu implements Menu {

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23};
    private static final int BACK = 31;

    private final ClanGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Clan clan;
    private final Inventory inventory;

    TagColorMenu(ClanGui gui, Player viewer, Clan clan) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.clan = clan;
        this.inventory = Bukkit.createInventory(this, 36, messages.get(viewer, "tag-color.title"));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK) {
            Sounds.page(player);
            gui.open(player);
            return;
        }
        TagColor[] colors = TagColor.values();
        for (int i = 0; i < SLOTS.length && i < colors.length; i++) {
            if (SLOTS[i] == slot) {
                Sounds.click(player);
                gui.clans().setTagColor(player, clan, colors[i], () -> gui.openTagColor(player));
                return;
            }
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        TagColor[] colors = TagColor.values();
        for (int i = 0; i < SLOTS.length && i < colors.length; i++) {
            TagColor color = colors[i];
            boolean current = color == clan.color();
            Component preview = messages.render("<gray>[" + color.paint(clan.tag()) + "]</gray>",
                    net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.empty());
            inventory.setItem(SLOTS[i], Items.item(color.icon(), messages.get(viewer, "tag-color.item",
                    Placeholder.component("preview", preview), "color", messages.plain(viewer, "tag-color." + color.id())),
                    List.of(messages.get(viewer, current ? "tag-color.current" : "tag-color.choose")), current));
        }
        inventory.setItem(BACK, Items.item(Material.OAK_DOOR, messages.get(viewer, "menu.back-to-clan"), List.of()));
    }
}
