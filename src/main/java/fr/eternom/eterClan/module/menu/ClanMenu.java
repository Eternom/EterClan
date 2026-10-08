package fr.eternom.eterClan.module.menu;

import fr.eternom.eterClan.module.bank.BankService;
import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanPermission;
import fr.eternom.eterClan.module.sync.ClanSync;
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
 * Menu du clan, 5 lignes :
 * <pre>
 *  ▣ ▣ ▢ ▢ ▢ ▢ ▢ ▣ ▣
 *  ▣ ☺ · · ⚑ · · ♟ ▣     ☺ = moi (mon compte ; clic : grade ou tag) · ⚑ = le clan · ♟ = membres
 *  ▢ ⇩ ⇧ · $ ¢ · % ▢     ⇩ ⇧ = mon compte · $ ¢ = réserve (donner, prendre) · % = intérêt
 *  ▣ ■ · ◎ · □ · ▦ ▣     ■ = prendre ce chunk · ◎ = voir le terrain · □ = rendre ce chunk · ▦ = parcelles
 *  ✖ ▣ ▢ ▢ ← ▢ ▢ ▣ ▣     ✖ = quitter (ou dissoudre pour le chef)
 * </pre>
 * Un bouton dont on n'a pas la permission est grisé et le dit. Tout ce qui coûte ou détruit demande confirmation.
 */
class ClanMenu implements Menu {

    private static final int HEAD = 10;
    private static final int INFO = 13;
    private static final int MEMBERS = 16;
    private static final int DEPOSIT = 19;
    private static final int WITHDRAW = 20;
    private static final int GIVE = 22;
    private static final int TAKE = 23;
    private static final int INTEREST = 25;
    private static final int CLAIM = 28;
    private static final int HERE = 30;
    private static final int UNCLAIM = 32;
    private static final int ZONES = 34;
    private static final int LEAVE = 36;
    private static final int BACK = 40;

    private final ClanGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Clan clan;
    private final Inventory inventory;

    ClanMenu(ClanGui gui, Player viewer, Clan clan) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.clan = clan;
        this.inventory = Bukkit.createInventory(this, 45, messages.get(viewer, "menu.title", "clan", clan.name(), "tag", clan.tag()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Runnable reopen = () -> gui.open(player);
        switch (slot) {
            case HEAD -> {
                Sounds.click(player);
                gui.clans().toggleDisplay(player, reopen);
            }
            case MEMBERS -> {
                Sounds.page(player);
                gui.openMembers(player);
            }
            case DEPOSIT -> gui.askAmount(player, "deposit", amount -> gui.bank().deposit(player, amount, reopen), reopen);
            case WITHDRAW -> gui.askAmount(player, "withdraw", amount -> gui.bank().withdraw(player, amount, reopen), reopen);
            case GIVE -> gui.askAmount(player, "give", amount -> gui.bank().giveToReserve(player, amount, reopen), reopen);
            case TAKE -> allowed(player, ClanPermission.RESERVE,
                    () -> gui.askAmount(player, "take", amount -> gui.bank().takeFromReserve(player, amount, reopen), reopen));
            case INTEREST -> allowed(player, ClanPermission.INTEREST,
                    () -> gui.askAmount(player, "interest", rate -> gui.bank().setInterest(player, rate, reopen), reopen));
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
            case LEAVE -> {
                if (clan.isOwner(player.getUniqueId())) {
                    gui.confirm(player, "disband", () -> gui.clans().disband(player), reopen, "clan", clan.name(),
                            "reserve", Money.format(clan.reserve()));
                } else {
                    gui.confirm(player, "leave", () -> gui.clans().leave(player), reopen, "clan", clan.name());
                }
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

    private void allowed(Player player, ClanPermission permission, Runnable action) {
        if (gui.refreshed(clan).can(player.getUniqueId(), permission)) {
            Sounds.click(player);
            action.run();
        } else {
            messages.send(player, "clan.no-permission");
        }
    }

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        double account = clan.member(viewer.getUniqueId()).map(Clan.Member::account).orElse(0.0);
        inventory.setItem(HEAD, Items.head(viewer.getPlayerProfile(), text("menu.head.name", "player", viewer.getName()), List.of(
                text("menu.head.account", "amount", Money.format(account)),
                text(clan.isOwner(viewer.getUniqueId()) ? "menu.head.owner" : "menu.head.member"),
                text(showsRank() ? "menu.head.display-rank" : "menu.head.display-clan", "tag", clan.tag()),
                text("menu.head.display-click"))));
        long seconds = Math.max(0, (clan.nextCycle() - System.currentTimeMillis()) / 1000);
        inventory.setItem(INFO, Items.item(Material.WHITE_BANNER, text("menu.info.name", "clan", clan.name(), "tag", clan.tag()), List.of(
                text("menu.info.members", "count", String.valueOf(clan.members().size())),
                text("menu.info.chunks", "count", String.valueOf(clan.chunks()), "free", String.valueOf(gui.land().pricing().free())),
                text("menu.info.reserve", "amount", Money.format(clan.reserve())),
                text("menu.info.accounts", "amount", Money.format(clan.accounts())),
                text("menu.info.interest", "rate", BankService.percent(clan.interest())),
                text("menu.info.upkeep", "amount", Money.format(gui.land().pricing().upkeepOf(clan.chunks()))),
                text("menu.info.next", "time", EterLib.get().formatDuration(viewer, seconds)))));
        inventory.setItem(MEMBERS, Items.item(Material.PLAYER_HEAD, text("menu.members.name"),
                List.of(text("menu.members.lore", "count", String.valueOf(clan.members().size())))));
        inventory.setItem(DEPOSIT, Items.item(Material.HOPPER, text("menu.deposit.name"), List.of(text("menu.deposit.lore"))));
        inventory.setItem(WITHDRAW, Items.item(Material.DISPENSER, text("menu.withdraw.name"), List.of(text("menu.withdraw.lore"))));
        inventory.setItem(GIVE, Items.item(Material.GOLD_INGOT, text("menu.give.name"), List.of(text("menu.give.lore"))));
        inventory.setItem(TAKE, button(Material.GOLD_BLOCK, "menu.take", ClanPermission.RESERVE));
        inventory.setItem(INTEREST, button(Material.GOLD_NUGGET, "menu.interest", ClanPermission.INTEREST));
        if (gui.land().isEnabled()) {
            inventory.setItem(CLAIM, button(Material.GRASS_BLOCK, "menu.claim", ClanPermission.CLAIM));
            inventory.setItem(HERE, Items.item(Material.COMPASS, text("menu.here.name"), List.of(text("menu.here.lore"))));
            inventory.setItem(UNCLAIM, button(Material.COARSE_DIRT, "menu.unclaim", ClanPermission.CLAIM));
            inventory.setItem(ZONES, Items.item(Material.OAK_FENCE, text("menu.zones.name"),
                    List.of(text("menu.zones.lore", "count", String.valueOf(gui.zonesOf(clan).size())))));
        } else {
            inventory.setItem(HERE, Items.item(Material.BARRIER, text("menu.no-land.name"), List.of(text("menu.no-land.lore"))));
        }
        inventory.setItem(LEAVE, Items.item(Material.RED_BANNER,
                text(clan.isOwner(viewer.getUniqueId()) ? "menu.disband.name" : "menu.leave.name"),
                List.of(text(clan.isOwner(viewer.getUniqueId()) ? "menu.disband.lore" : "menu.leave.lore"))));
        inventory.setItem(BACK, gui.back().item(viewer));
    }

    /** Affiche son grade (préférence, ou staff) plutôt que le tag du clan. */
    private boolean showsRank() {
        return viewer.hasPermission(ClanSync.STAFF)
                || clan.member(viewer.getUniqueId()).map(Clan.Member::showRank).orElse(false);
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
