package fr.eternom.eterClan.module.menu;

import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.Clan.Member;
import fr.eternom.eterClan.module.clan.ClanPermission;
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
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * La fiche d'un membre, 3 lignes : sa tête, ses permissions (PERMISSIONS), l'exclure (KICK, avec confirmation), lui
 * céder le clan (le chef seulement, avec confirmation). Rien sur le chef lui-même.
 */
class MemberMenu implements Menu {

    private static final int HEAD = 10;
    private static final int PERMISSIONS = 12;
    private static final int KICK = 14;
    private static final int TRANSFER = 16;
    private static final int BACK = 22;

    private final ClanGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Clan clan;
    private final Member member;
    private final Inventory inventory;

    MemberMenu(ClanGui gui, Player viewer, Clan clan, Member member) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.clan = clan;
        this.member = member;
        this.inventory = Bukkit.createInventory(this, 27, messages.get(viewer, "member.title", "player", member.name()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        boolean owner = clan.isOwner(member.uuid());
        Runnable back = () -> gui.openMember(player, member.uuid());
        switch (slot) {
            case PERMISSIONS -> {
                if (allowed(player, ClanPermission.PERMISSIONS) && !owner) {
                    Sounds.page(player);
                    gui.openPermissions(player, member.uuid());
                }
            }
            case KICK -> {
                if (allowed(player, ClanPermission.KICK) && !owner) {
                    gui.confirm(player, "kick", () -> gui.clans().kick(player, member.uuid(), () -> gui.openMembers(player)), back,
                            "player", member.name(), "amount", Money.format(member.account()));
                }
            }
            case TRANSFER -> {
                if (clan.isOwner(player.getUniqueId()) && !owner) {
                    gui.confirm(player, "transfer", () -> gui.clans().transfer(player, member.uuid(), () -> gui.open(player)), back,
                            "player", member.name());
                }
            }
            case BACK -> {
                Sounds.page(player);
                gui.openMembers(player);
            }
            default -> {
            }
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private boolean allowed(Player player, ClanPermission permission) {
        if (clan.can(player.getUniqueId(), permission)) {
            return true;
        }
        messages.send(player, "clan.no-permission");
        return false;
    }

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        boolean owner = clan.isOwner(member.uuid());
        inventory.setItem(HEAD, Items.head(Bukkit.createProfile(member.uuid(), member.name()),
                messages.get(viewer, "members.name", "player", member.name()), List.of(
                        messages.get(viewer, "members.account", "amount", Money.format(member.account())),
                        messages.get(viewer, owner ? "member.is-owner" : "member.permissions",
                                "count", String.valueOf(member.permissions().size())))));
        if (owner) {
            inventory.setItem(PERMISSIONS, Items.item(Material.NAME_TAG, messages.get(viewer, "member.owner-all.name"),
                    List.of(messages.get(viewer, "member.owner-all.lore"))));
        } else {
            inventory.setItem(PERMISSIONS, button(Material.NAME_TAG, "member.permissions-button", ClanPermission.PERMISSIONS));
            inventory.setItem(KICK, button(Material.IRON_DOOR, "member.kick", ClanPermission.KICK));
            if (clan.isOwner(viewer.getUniqueId())) {
                inventory.setItem(TRANSFER, Items.item(Material.GOLDEN_HELMET, messages.get(viewer, "member.transfer.name"),
                        List.of(messages.get(viewer, "member.transfer.lore"))));
            }
        }
        inventory.setItem(BACK, Items.item(Material.ARROW, messages.get(viewer, "member.back"), List.of()));
    }

    private ItemStack button(Material icon, String key, ClanPermission permission) {
        boolean can = clan.can(viewer.getUniqueId(), permission);
        return Items.item(can ? icon : Material.GRAY_DYE, messages.get(viewer, key + ".name"),
                List.of(messages.get(viewer, can ? key + ".lore" : "menu.locked")));
    }
}
