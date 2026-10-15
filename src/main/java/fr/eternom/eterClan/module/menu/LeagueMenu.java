package fr.eternom.eterClan.module.menu;

import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.ranking.ClanLeague;
import fr.eternom.eterClan.module.ranking.Standing;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.message.Numbers;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Le classement des clans du mois, 6 lignes :
 * <pre>
 *  ▣ ▣ ▢ ▢ ? ▢ ▢ ▣ ▣     ? = comment ça marche, fin du mois, prix du top 3
 *  ▣ · · · ⚑ · · · ▣     ⚑ = mon clan : place et score
 *  ▢ · ⌚ · ✎ · $ · ▢    les 4 critères de mon clan : activité, travail, trésor, expansion
 *  ▣ · · · ▦ · · · ▣
 *  ▢ · 1 2 3 4 5 · ▢     les 5 premiers clans
 *  ▣ ▣ ▢ ▢ ← ▢ ▢ ▣ ▣
 * </pre>
 */
class LeagueMenu implements Menu {

    private static final int INFO = 4;
    private static final int MINE = 13;
    private static final int[] CRITERIA = {20, 22, 24, 31};
    private static final int[] TOP = {38, 39, 40, 41, 42};
    private static final int BACK = 49;

    private final ClanGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Inventory inventory;

    LeagueMenu(ClanGui gui, Player viewer, Clan clan) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "league.title"));
        render(clan);
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK) {
            Sounds.page(player);
            gui.open(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render(Clan clan) {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        ClanLeague league = gui.league();
        long seconds = Math.max(0, (league.seasonEnd() - System.currentTimeMillis()) / 1000);
        List<Component> info = new ArrayList<>(List.of(text("league.info.how"), text("league.info.how2"), Component.empty(),
                text("league.info.ends", "time", EterLib.get().formatDuration(viewer, seconds)), text("league.info.rewards")));
        List<Double> rewards = league.rewards();
        for (int place = 1; place <= rewards.size(); place++) {
            info.add(text("league.info.reward", "place", String.valueOf(place), "amount", Money.format(rewards.get(place - 1))));
        }
        inventory.setItem(INFO, Items.item(Material.BOOK, text("league.info.name"), info));

        Optional<ClanLeague.Named> mine = league.of(clan.id());
        Standing standing = mine.map(ClanLeague.Named::standing).orElse(new Standing(clan.id(), 0, 0, 0, 0, 0, 0));
        inventory.setItem(MINE, Items.item(Material.WHITE_BANNER, text("menu.info.name", "clan", clan.name(), "tag", clan.tag()),
                mine.isPresent()
                        ? List.of(text("menu.league.place", "place", String.valueOf(standing.place()),
                        "count", String.valueOf(league.current().size())), text("menu.league.score", "score", ClanMenu.score(standing.score())))
                        : List.of(text("menu.league.unranked"))));
        criterion(CRITERIA[0], Material.CLOCK, "activity", hours(standing.activity()));
        criterion(CRITERIA[1], Material.WRITABLE_BOOK, "work", Numbers.format(standing.work()));
        criterion(CRITERIA[2], Material.GOLD_INGOT, "treasury", Money.format(standing.treasury() / 100.0));
        criterion(CRITERIA[3], Material.GRASS_BLOCK, "expansion", Numbers.format(standing.expansion()));

        List<ClanLeague.Named> top = league.current();
        for (int i = 0; i < TOP.length; i++) {
            if (i < top.size()) {
                ClanLeague.Named named = top.get(i);
                inventory.setItem(TOP[i], Items.item(named.standing().clanId() == clan.id() ? Material.ORANGE_BANNER : Material.WHITE_BANNER,
                        text("league.top.name", "place", String.valueOf(named.standing().place()), "tag", named.tag(), "clan", named.name()),
                        List.of(text("menu.league.score", "score", ClanMenu.score(named.standing().score())))));
            } else {
                inventory.setItem(TOP[i], Items.item(Material.LIGHT_GRAY_BANNER, text("league.top.empty", "place", String.valueOf(i + 1)), List.of()));
            }
        }
        inventory.setItem(BACK, Items.item(Material.OAK_DOOR, text("menu.back-to-clan"), List.of()));
    }

    private void criterion(int slot, Material icon, String id, String value) {
        inventory.setItem(slot, Items.item(icon, text("league.criterion." + id + ".name"), List.of(
                text("league.criterion.value", "value", value),
                text("league.criterion." + id + ".lore"))));
    }

    /** Minutes en « 12 h 34 ». */
    private static String hours(long minutes) {
        return Numbers.format(minutes / 60) + " h " + String.format("%02d", minutes % 60);
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
