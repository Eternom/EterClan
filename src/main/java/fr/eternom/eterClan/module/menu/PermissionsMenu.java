package fr.eternom.eterClan.module.menu;

import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.Clan.Member;
import fr.eternom.eterClan.module.clan.ClanPermission;
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
 * Les permissions d'un membre : une icône par permission, brillante si elle est donnée ; clic = donner ou retirer
 * (permission PERMISSIONS ; le chef a tout, on ne touche pas aux siennes). Rappel : elles ne valent jamais dans une
 * parcelle louée.
 */
class PermissionsMenu implements Menu {

    private static final List<Integer> SLOTS = List.of(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24);
    private static final int BACK = 40;

    private final ClanGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Clan clan;
    private final Member member;
    private final Inventory inventory;

    PermissionsMenu(ClanGui gui, Player viewer, Clan clan, Member member) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.clan = clan;
        this.member = member;
        this.inventory = Bukkit.createInventory(this, 45, messages.get(viewer, "permissions.title", "player", member.name()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        int index = SLOTS.indexOf(slot);
        if (index >= 0 && index < ClanPermission.values().length) {
            Sounds.click(player);
            gui.clans().togglePermission(player, clan, member.uuid(), ClanPermission.values()[index],
                    () -> gui.openPermissions(player, member.uuid()));
        } else if (slot == BACK) {
            Sounds.page(player);
            gui.openMembers(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        ClanPermission[] permissions = ClanPermission.values();
        boolean owner = clan.isOwner(member.uuid());
        for (int i = 0; i < permissions.length && i < SLOTS.size(); i++) {
            ClanPermission permission = permissions[i];
            boolean has = owner || member.permissions().contains(permission);
            inventory.setItem(SLOTS.get(i), Items.item(permission.icon(),
                    messages.get(viewer, "permission." + permission.id()),
                    List.of(messages.get(viewer, "permission-lore." + permission.id()),
                            messages.get(viewer, has ? "permissions.granted" : "permissions.denied")), has));
        }
        inventory.setItem(31, Items.item(Material.BOOK, messages.get(viewer, "permissions.note.name"),
                List.of(messages.get(viewer, "permissions.note.lore"))));
        inventory.setItem(BACK, Items.item(Material.ARROW, messages.get(viewer, "permissions.back"), List.of()));
    }
}
