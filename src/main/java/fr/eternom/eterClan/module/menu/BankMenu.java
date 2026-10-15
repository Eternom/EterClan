package fr.eternom.eterClan.module.menu;

import fr.eternom.eterClan.module.bank.BankService;
import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanPermission;
import fr.eternom.eterLib.EterLib;
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
 * La banque du clan, 5 lignes :
 * <pre>
 *  ▣ ▣ ▢ ▢ $ ▢ ▢ ▣ ▣     $ = résumé (mon compte, réserve, comptes, intérêt, prochain passage)
 *  ▣ · · · · · · · ▣
 *  ▢ ⇩ ⇧ · · · + − ▢     ⇩ ⇧ = mon compte (ranger, reprendre) · + − = réserve (donner, prendre)
 *  ▣ · · · % · · · ▣     % = intérêt
 *  ▣ ▣ ▢ ▢ ← ▢ ▢ ▣ ▣
 * </pre>
 * Un bouton dont on n'a pas la permission est grisé et le dit ; « Donner à la réserve » affiche le temps de jeu qui
 * manque à un nouveau joueur (giveWait, en minutes).
 */
class BankMenu implements Menu {

    private static final int INFO = 4;
    private static final int DEPOSIT = 19;
    private static final int WITHDRAW = 20;
    private static final int GIVE = 24;
    private static final int TAKE = 25;
    private static final int INTEREST = 31;
    private static final int BACK = 40;

    private final ClanGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Clan clan;
    private final long giveWait;
    private final Inventory inventory;

    BankMenu(ClanGui gui, Player viewer, Clan clan, long giveWait) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.clan = clan;
        this.giveWait = giveWait;
        this.inventory = Bukkit.createInventory(this, 45, messages.get(viewer, "bank-menu.title", "clan", clan.name()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Runnable reopen = () -> gui.openBank(player);
        switch (slot) {
            case DEPOSIT -> gui.askAmount(player, "deposit", amount -> gui.bank().deposit(player, amount, reopen), reopen);
            case WITHDRAW -> gui.askAmount(player, "withdraw", amount -> gui.bank().withdraw(player, amount, reopen), reopen);
            case GIVE -> {
                if (giveWait > 0) {
                    Sounds.refuse(player);
                } else {
                    gui.askAmount(player, "give", amount -> gui.bank().giveToReserve(player, amount, reopen), reopen);
                }
            }
            case TAKE -> allowed(player, ClanPermission.RESERVE,
                    () -> gui.askAmount(player, "take", amount -> gui.bank().takeFromReserve(player, amount, reopen), reopen));
            case INTEREST -> allowed(player, ClanPermission.INTEREST,
                    () -> gui.askAmount(player, "interest", rate -> gui.bank().setInterest(player, rate, reopen), reopen));
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
        double account = clan.member(viewer.getUniqueId()).map(Clan.Member::account).orElse(0.0);
        long seconds = Math.max(0, (clan.nextCycle() - System.currentTimeMillis()) / 1000);
        inventory.setItem(INFO, Items.item(Material.GOLD_BLOCK, text("bank-menu.info.name"), List.of(
                text("menu.head.account", "amount", Money.format(account)),
                text("menu.info.reserve", "amount", Money.format(clan.reserve())),
                text("menu.info.accounts", "amount", Money.format(clan.accounts())),
                text("menu.info.interest", "rate", BankService.percent(clan.interest())),
                text("menu.info.next", "time", EterLib.get().formatDuration(viewer, seconds)))));
        inventory.setItem(DEPOSIT, Items.item(Material.HOPPER, text("menu.deposit.name"), List.of(text("menu.deposit.lore"))));
        inventory.setItem(WITHDRAW, Items.item(Material.DISPENSER, text("menu.withdraw.name"), List.of(text("menu.withdraw.lore"))));
        inventory.setItem(GIVE, giveWait > 0
                ? Items.item(Material.GRAY_DYE, text("menu.give.name"), List.of(text("menu.give.lore"), Component.empty(),
                text("bank-menu.give-locked", "hours", String.valueOf(gui.bank().giveMinHours()),
                        "time", EterLib.get().formatDuration(viewer, giveWait * 60))))
                : Items.item(Material.GOLD_INGOT, text("menu.give.name"), List.of(text("menu.give.lore"), text("bank-menu.give-league"))));
        inventory.setItem(TAKE, button(Material.GOLD_BLOCK, "menu.take", ClanPermission.RESERVE));
        inventory.setItem(INTEREST, button(Material.GOLD_NUGGET, "menu.interest", ClanPermission.INTEREST));
        inventory.setItem(BACK, Items.item(Material.OAK_DOOR, text("menu.back-to-clan"), List.of()));
    }

    /** Bouton qui demande une permission : sa description, ou « tu n'as pas la permission ». */
    private ItemStack button(Material icon, String key, ClanPermission permission) {
        boolean can = clan.can(viewer.getUniqueId(), permission);
        return Items.item(can ? icon : Material.GRAY_DYE, text(key + ".name"),
                List.of(text(can ? key + ".lore" : "menu.locked")));
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
