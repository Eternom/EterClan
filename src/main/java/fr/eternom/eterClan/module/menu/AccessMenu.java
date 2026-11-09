package fr.eternom.eterClan.module.menu;

import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanPermission;
import fr.eternom.eterClan.module.clan.Land;
import fr.eternom.eterClan.module.clan.Land.PassOffer;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Qui entre sur le terrain (ACCESS ; inviter : INVITE) : le mode (ouvert, fermé, à pass), l'offre de pass (prix,
 * durée) et ce qu'il permet, les passes en cours, les invités (accès gratuit pour quelques heures), la liste noire.
 */
class AccessMenu implements Menu {

    private static final int MODE = 11;
    private static final int OFFER = 13;
    private static final int PASSES = 15;
    private static final int GUEST = 20;
    private static final int BANNED = 24;
    private static final int[] RIGHT_SLOTS = {29, 30, 31, 32, 33};
    private static final int BACK = 49;

    private final ClanGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Clan clan;
    private final Inventory inventory;
    private final Map<Integer, ClanPermission> rightAt = new HashMap<>();

    AccessMenu(ClanGui gui, Player viewer, Clan clan) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.clan = clan;
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "access-menu.title", "clan", clan.name()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Clan current = gui.refreshed(clan);
        Runnable reopen = () -> gui.openAccess(player);
        ClanPermission right = rightAt.get(slot);
        if (right != null) {
            Sounds.click(player);
            gui.accessService().toggleRight(player, current, right, reopen);
            return;
        }
        switch (slot) {
            case MODE -> {
                Sounds.click(player);
                gui.accessService().cycleMode(player, current, reopen);
            }
            case OFFER -> gui.askAmount(player, "pass-price", price -> gui.askAmount(player, "pass-days",
                    days -> gui.accessService().setOffer(player, current, price, (int) days, reopen), reopen), reopen);
            case PASSES -> {
                Sounds.page(player);
                gui.openPasses(player);
            }
            case GUEST -> gui.askText(player, "guest-name", "", name -> {
                if (!name.isEmpty()) {
                    gui.askAmount(player, "guest-hours", hours -> gui.accessService().invite(player, current, name, (int) hours, reopen), reopen);
                }
            }, reopen);
            case BANNED -> gui.askText(player, "banned-name", "", name -> {
                if (!name.isEmpty()) {
                    gui.accessService().toggleBanned(player, current, name, reopen);
                }
            }, reopen);
            case BACK -> {
                Sounds.page(player);
                gui.openLand(player);
            }
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
        Land land = clan.land();
        boolean manage = clan.can(viewer.getUniqueId(), ClanPermission.ACCESS);
        String mode = land.mode().name().toLowerCase();
        inventory.setItem(MODE, Items.item(manage ? switch (land.mode()) {
            case OPEN -> Material.OAK_DOOR;
            case CLOSED -> Material.IRON_DOOR;
            case PASS -> Material.GOLDEN_HELMET;
        } : Material.GRAY_DYE, text("access-menu.mode.name", "mode", messages.plain(viewer, "access.mode." + mode)),
                List.of(text("access-menu.mode." + mode), text(manage ? "access-menu.mode.click" : "menu.locked"))));
        PassOffer offer = land.offer();
        inventory.setItem(OFFER, Items.item(manage ? Material.GOLD_INGOT : Material.GRAY_DYE, text("access-menu.offer.name"), List.of(
                text(offer.forSale() ? "access-menu.offer.price" : "access-menu.offer.none", "price", Money.format(offer.price()),
                        "days", String.valueOf(offer.days())),
                text(manage ? "access-menu.offer.click" : "menu.locked"))));
        long now = System.currentTimeMillis();
        long active = land.passes().stream().filter(pass -> pass.isValid(now)).count();
        inventory.setItem(PASSES, Items.item(Material.PAPER, text("access-menu.passes.name", "count", String.valueOf(active)),
                List.of(text("access-menu.passes.lore"))));
        boolean invite = clan.can(viewer.getUniqueId(), ClanPermission.INVITE);
        inventory.setItem(GUEST, Items.item(invite ? Material.CAKE : Material.GRAY_DYE, text("access-menu.guest.name"),
                List.of(text(invite ? "access-menu.guest.lore" : "menu.locked"))));
        List<Component> bannedLore = new ArrayList<>();
        bannedLore.add(text("access-menu.banned.count", "count", String.valueOf(land.banned().size())));
        bannedLore.add(text(manage ? "access-menu.banned.click" : "menu.locked"));
        inventory.setItem(BANNED, Items.item(manage ? Material.BARRIER : Material.GRAY_DYE, text("access-menu.banned.name"), bannedLore));
        int i = 0;
        for (ClanPermission right : List.of(ClanPermission.DOORS, ClanPermission.REDSTONE, ClanPermission.CROPS,
                ClanPermission.ANIMALS, ClanPermission.CONTAINERS)) {
            int slot = RIGHT_SLOTS[i++];
            rightAt.put(slot, right);
            boolean on = offer.rights().contains(right);
            inventory.setItem(slot, Items.item(on ? right.icon() : Material.GRAY_DYE,
                    text("access-menu.right", "permission", messages.plain(viewer, "permission." + right.id())),
                    List.of(text(on ? "access-menu.right-on" : "access-menu.right-off"), text("access-menu.right-click")), on));
        }
        inventory.setItem(BACK, Items.item(Material.OAK_DOOR, text("menu.back-to-clan"), List.of()));
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
