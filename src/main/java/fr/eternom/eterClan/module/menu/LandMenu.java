package fr.eternom.eterClan.module.menu;

import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanPermission;
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
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Le terrain du clan, 5 lignes :
 * <pre>
 *  ▣ ▣ ▢ ▢ ▦ ▢ ▢ ▣ ▣     ▦ = résumé (chunks, gratuits, entretien, prix du prochain)
 *  ▣ · · · · · · · ▣
 *  ▢ · ■ · ◎ · □ · ▢     ■ = prendre ce chunk · ◎ = voir le terrain · □ = rendre ce chunk
 *  ▣ · · ▤ · ✎ · · ▣     ▤ = parcelles · ✎ = règles du territoire
 *  ▣ ▣ ▢ ▢ ← ▢ ▢ ▣ ▣
 * </pre>
 */
class LandMenu implements Menu {

    private static final int INFO = 4;
    private static final int CLAIM = 20;
    private static final int HERE = 22;
    private static final int UNCLAIM = 24;
    private static final int ZONES = 30;
    private static final int RULES = 32;
    private static final int BACK = 40;

    private final ClanGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Clan clan;
    private final Inventory inventory;

    LandMenu(ClanGui gui, Player viewer, Clan clan) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.clan = clan;
        this.inventory = Bukkit.createInventory(this, 45, messages.get(viewer, "land-menu.title", "clan", clan.name()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Runnable reopen = () -> gui.openLand(player);
        switch (slot) {
            case CLAIM -> allowed(player, ClanPermission.CLAIM, () -> gui.confirm(player, "claim", () -> gui.land().claim(player), reopen,
                    "price", Money.format(gui.land().pricing().priceOf(clan.chunks() + 1)),
                    "upkeep", Money.format(gui.land().pricing().upkeepOf(clan.chunks() + 1))));
            case HERE -> {
                player.closeInventory();
                gui.land().here(player);
            }
            case UNCLAIM -> allowed(player, ClanPermission.CLAIM,
                    () -> gui.confirm(player, "unclaim", () -> gui.land().unclaim(player), reopen));
            case ZONES -> {
                Sounds.page(player);
                gui.openZones(player);
            }
            case RULES -> {
                Sounds.page(player);
                gui.openRules(player);
            }
            case BACK -> {
                Sounds.page(player);
                gui.open(player);
            }
            default -> {
            }
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void allowed(Player player, ClanPermission permission, Runnable action) {
        if (gui.refreshed(clan).can(player.getUniqueId(), permission)) {
            Sounds.click(player);
            action.run();
        } else {
            Sounds.refuse(player);
            messages.send(player, "clan.no-permission");
        }
    }

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        var pricing = gui.land().pricing();
        inventory.setItem(INFO, Items.item(Material.FILLED_MAP, text("land-menu.info.name"), List.of(
                text("menu.info.chunks", "count", String.valueOf(clan.chunks()), "free", String.valueOf(pricing.free())),
                text("menu.info.upkeep", "amount", Money.format(pricing.upkeepOf(clan.chunks()))),
                text("land-menu.info.next-price", "price", Money.format(pricing.priceOf(clan.chunks() + 1))),
                text("land-menu.info.league"))));
        inventory.setItem(CLAIM, button(Material.GRASS_BLOCK, "menu.claim", ClanPermission.CLAIM));
        inventory.setItem(HERE, Items.item(Material.COMPASS, text("menu.here.name"), List.of(text("menu.here.lore"))));
        inventory.setItem(UNCLAIM, button(Material.COARSE_DIRT, "menu.unclaim", ClanPermission.CLAIM));
        inventory.setItem(ZONES, Items.item(Material.OAK_FENCE, text("menu.zones.name"),
                List.of(text("menu.zones.lore", "count", String.valueOf(gui.zonesOf(clan).size())))));
        inventory.setItem(RULES, Items.item(Material.WRITABLE_BOOK, text("menu.rules.name"), List.of(text("menu.rules.lore"))));
        inventory.setItem(BACK, Items.item(Material.OAK_DOOR, text("menu.back-to-clan"), List.of()));
    }

    private ItemStack button(Material icon, String key, ClanPermission permission) {
        boolean can = clan.can(viewer.getUniqueId(), permission);
        return Items.item(can ? icon : Material.GRAY_DYE, text(key + ".name"),
                List.of(text(can ? key + ".lore" : "menu.locked")));
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
