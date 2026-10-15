package fr.eternom.eterClan.module.menu;

import fr.eternom.eterClan.module.bank.BankService;
import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.ranking.ClanLeague;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Menu du clan, 5 lignes : l'accueil, d'où partent les sous-menus.
 * <pre>
 *  ▣ ▣ ▢ ▢ ▢ ▢ ▢ ▣ ▣
 *  ▣ ☺ · · ⚑ · · ♛ ▣     ☺ = moi (mon compte ; clic : grade ou tag) · ⚑ = le clan · ♛ = classement du mois
 *  ▢ · $ · ♟ · ▦ · ▢     $ = banque · ♟ = membres · ▦ = terrain et parcelles
 *  ▣ · · · ✎ · · · ▣     ✎ = règles du territoire (ce que chacun peut y faire)
 *  ✖ ▣ ▢ ▢ ← ▢ ▢ ▣ ▣     ✖ = quitter (ou dissoudre pour le chef)
 * </pre>
 */
class ClanMenu implements Menu {

    private static final int HEAD = 10;
    private static final int INFO = 13;
    private static final int LEAGUE = 16;
    private static final int BANK = 20;
    private static final int MEMBERS = 22;
    private static final int LAND = 24;
    private static final int RULES = 31;
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
        switch (slot) {
            case HEAD -> {
                Sounds.click(player);
                gui.clans().toggleDisplay(player, () -> gui.open(player));
            }
            case LEAGUE -> page(player, () -> gui.openLeague(player));
            case BANK -> page(player, () -> gui.openBank(player));
            case MEMBERS -> page(player, () -> gui.openMembers(player));
            case LAND -> {
                if (gui.land().isEnabled()) {
                    page(player, () -> gui.openLand(player));
                }
            }
            case RULES -> page(player, () -> gui.openRules(player));
            case LEAVE -> {
                Runnable reopen = () -> gui.open(player);
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

    private static void page(Player player, Runnable open) {
        Sounds.page(player);
        open.run();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
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
        inventory.setItem(LEAGUE, leagueItem());
        inventory.setItem(BANK, Items.item(Material.GOLD_INGOT, text("menu.bank.name"), List.of(
                text("menu.head.account", "amount", Money.format(account)),
                text("menu.info.reserve", "amount", Money.format(clan.reserve())),
                Component.empty(), text("menu.open"))));
        inventory.setItem(MEMBERS, Items.item(Material.PLAYER_HEAD, text("menu.members.name"),
                List.of(text("menu.members.lore", "count", String.valueOf(clan.members().size())), Component.empty(), text("menu.open"))));
        if (gui.land().isEnabled()) {
            inventory.setItem(LAND, Items.item(Material.GRASS_BLOCK, text("menu.land.name"), List.of(
                    text("menu.land.chunks", "count", String.valueOf(clan.chunks())),
                    text("menu.land.zones", "count", String.valueOf(gui.zonesOf(clan).size())),
                    Component.empty(), text("menu.open"))));
        } else {
            inventory.setItem(LAND, Items.item(Material.BARRIER, text("menu.no-land.name"), List.of(text("menu.no-land.lore"))));
        }
        inventory.setItem(RULES, Items.item(Material.WRITABLE_BOOK, text("menu.rules.name"),
                List.of(text("menu.rules.lore"), Component.empty(), text("menu.open"))));
        inventory.setItem(LEAVE, Items.item(Material.RED_BANNER,
                text(clan.isOwner(viewer.getUniqueId()) ? "menu.disband.name" : "menu.leave.name"),
                List.of(text(clan.isOwner(viewer.getUniqueId()) ? "menu.disband.lore" : "menu.leave.lore"))));
        inventory.setItem(BACK, gui.back().item(viewer));
    }

    /** La place et le score du clan ce mois-ci, ou comment entrer au classement. */
    private ItemStack leagueItem() {
        List<Component> lore = new ArrayList<>();
        Optional<ClanLeague.Named> mine = gui.league().of(clan.id());
        if (mine.isPresent()) {
            lore.add(text("menu.league.place", "place", String.valueOf(mine.get().standing().place()),
                    "count", String.valueOf(gui.league().current().size())));
            lore.add(text("menu.league.score", "score", score(mine.get().standing().score())));
        } else {
            lore.add(text("menu.league.unranked"));
        }
        lore.add(Component.empty());
        lore.add(text("menu.open"));
        return Items.item(Material.GOLDEN_HELMET, text("menu.league.name"), lore);
    }

    /** Score en dixièmes : 875 -> « 87,5 ». */
    static String score(long tenths) {
        return String.format(Locale.FRANCE, "%.1f", tenths / 10.0);
    }

    /** Affiche son grade (préférence, ou staff) plutôt que le tag du clan. */
    private boolean showsRank() {
        return viewer.hasPermission(ClanSync.STAFF)
                || clan.member(viewer.getUniqueId()).map(Clan.Member::showRank).orElse(false);
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
