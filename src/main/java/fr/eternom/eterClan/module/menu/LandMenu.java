package fr.eternom.eterClan.module.menu;

import fr.eternom.eterClan.module.claim.ClaimIndex.ChunkKey;
import fr.eternom.eterClan.module.claim.ClaimService.Quote;
import fr.eternom.eterClan.module.claim.ClaimService.Upkeep;
import fr.eternom.eterClan.module.claim.LandPlan;
import fr.eternom.eterClan.module.claim.LandPlan.Plot;
import fr.eternom.eterClan.module.claim.LandRules;
import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanPermission;
import fr.eternom.eterClan.module.clan.Journal;
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
import java.util.Set;

/**
 * Le terrain du clan, 6 lignes :
 * <pre>
 *  ▣ ▣ ▣ ▣ ▦ ▣ ▣ ▣ ▣     ▦ = résumé : chunks (cœur, extensions, avant-postes), entretien détaillé, dette, niveau
 *  ▣ · · · · · · · ▣
 *  ▣ ■ ▩ ◎ · □ ⚿ ▤ ▣     ■ prendre ici · ▩ carte · ◎ voir les bords · □ rendre ici · ⚿ accès · ▤ parcelles
 *  ▣ ⌂ ⚑ ♥ · ✎ ☰ ✖ ▣     ⌂ home · ⚑ poser le home · ♥ déplacer le cœur · ✎ règles · ☰ journal · ✖ tout rendre
 *  ▣ · · · · · · · ▣
 *  ▣ ▣ ▣ ▣ ← ▣ ▣ ▣ ▣
 * </pre>
 */
class LandMenu implements Menu {

    private static final int INFO = 4;
    private static final int CLAIM = 19;
    private static final int MAP = 20;
    private static final int HERE = 21;
    private static final int UNCLAIM = 23;
    private static final int ACCESS = 24;
    private static final int ZONES = 25;
    private static final int HOME = 28;
    private static final int SETHOME = 29;
    private static final int HEART = 30;
    private static final int RULES = 32;
    private static final int JOURNAL = 33;
    private static final int ABANDON = 34;
    private static final int BACK = 49;

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
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "land-menu.title", "clan", clan.name()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Runnable reopen = () -> gui.openLand(player);
        switch (slot) {
            case CLAIM -> allowed(player, ClanPermission.CLAIM, () -> {
                Quote quote = quoteHere(player);
                if (!quote.allowed()) {
                    messages.send(player, quote.refusal(), "max", String.valueOf(gui.land().maxOutposts(clan)),
                            "size", String.valueOf(gui.land().rules().outpostSize()), "buffer", String.valueOf(gui.land().rules().buffer()));
                    return;
                }
                gui.confirm(player, "claim", () -> gui.land().claim(player), reopen, "price", Money.format(quote.price()),
                        "kind", messages.plain(player, "kind." + quote.kind().name().toLowerCase()));
            });
            case MAP -> {
                Sounds.page(player);
                gui.openMap(player, Set.of());
            }
            case HERE -> {
                player.closeInventory();
                gui.land().here(player);
            }
            case UNCLAIM -> allowed(player, ClanPermission.CLAIM,
                    () -> gui.confirm(player, "unclaim", () -> gui.land().unclaim(player), reopen));
            case ACCESS -> {
                Sounds.page(player);
                gui.openAccess(player);
            }
            case ZONES -> {
                Sounds.page(player);
                gui.openZones(player);
            }
            case HOME -> {
                player.closeInventory();
                gui.land().home(player);
            }
            case SETHOME -> allowed(player, ClanPermission.CLAIM, () -> {
                player.closeInventory();
                gui.land().setHome(player);
            });
            case HEART -> {
                LandRules rules = gui.land().rules();
                gui.confirm(player, "heart", () -> gui.land().moveHeart(player, reopen), reopen,
                        "price", Money.format(rules.heartMovePrice()), "days", String.valueOf(rules.heartMoveDays()));
            }
            case RULES -> {
                Sounds.page(player);
                gui.openRules(player);
            }
            case JOURNAL -> gui.showJournal(player, Journal.Category.CLAN);
            case ABANDON -> gui.confirm(player, "abandon", () -> gui.land().abandon(player, reopen), reopen,
                    "count", String.valueOf(clan.chunks()));
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

    private Quote quoteHere(Player player) {
        Clan current = gui.refreshed(clan);
        Plot plot = gui.land().plot(ChunkKey.of(player.getLocation()));
        return gui.land().quote(current, plot, current.land().plots(), current.land().heart());
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
        Upkeep upkeep = gui.land().upkeep(clan);
        int level = gui.land().level(clan);
        int outposts = LandPlan.outposts(clan.land().heart(), clan.land().plots()).size();
        List<Component> info = new ArrayList<>();
        info.add(text("land-menu.info.chunks", "core", String.valueOf(upkeep.core()), "extensions", String.valueOf(upkeep.extensions()),
                "outposts", String.valueOf(upkeep.outposts())));
        info.add(text("land-menu.info.outposts", "count", String.valueOf(outposts), "max", String.valueOf(gui.land().maxOutposts(clan)),
                "size", String.valueOf(gui.land().rules().outpostSize())));
        info.add(text("land-menu.info.upkeep", "amount", Money.format(upkeep.total())));
        if (upkeep.discount() > 0) {
            info.add(text("land-menu.info.discount", "discount", String.valueOf(upkeep.discount())));
        }
        if (clan.land().debt() > 0) {
            info.add(text("land-menu.info.debt", "debt", Money.format(clan.land().debt())));
        }
        info.add(text("land-menu.info.level", "level", String.valueOf(level)));
        gui.land().rules().next(level).ifPresent(next -> info.add(text("land-menu.info.next-level",
                "members", String.valueOf(next.members()), "chunks", String.valueOf(next.chunks()), "outposts", String.valueOf(next.outposts()))));
        Quote here = quoteHere(viewer);
        info.add(here.allowed() ? text("land-menu.info.here-price", "kind", messages.plain(viewer, "kind." + here.kind().name().toLowerCase()),
                "price", Money.format(here.price())) : text("land-menu.info.here-refused", "reason", refusal(here.refusal())));
        inventory.setItem(INFO, Items.item(Material.FILLED_MAP, text("land-menu.info.name"), info));
        inventory.setItem(CLAIM, button(Material.GRASS_BLOCK, "menu.claim", ClanPermission.CLAIM));
        inventory.setItem(MAP, Items.item(Material.MAP, text("menu.map.name"), List.of(text("menu.map.lore"))));
        inventory.setItem(HERE, Items.item(Material.COMPASS, text("menu.here.name"), List.of(text("menu.here.lore"))));
        inventory.setItem(UNCLAIM, button(Material.COARSE_DIRT, "menu.unclaim", ClanPermission.CLAIM));
        inventory.setItem(ACCESS, Items.item(Material.IRON_BARS, text("menu.access.name"), List.of(text("menu.access.lore",
                "mode", messages.plain(viewer, "access.mode." + clan.land().mode().name().toLowerCase())))));
        inventory.setItem(ZONES, Items.item(Material.OAK_FENCE, text("menu.zones.name"),
                List.of(text("menu.zones.lore", "count", String.valueOf(gui.zonesOf(clan).size())))));
        inventory.setItem(HOME, Items.item(Material.RED_BED, text("menu.home.name"), List.of(text(clan.land().home() == null
                ? "menu.home.none" : "menu.home.lore"))));
        inventory.setItem(SETHOME, button(Material.WHITE_BANNER, "menu.sethome", ClanPermission.CLAIM));
        boolean owner = clan.isOwner(viewer.getUniqueId());
        inventory.setItem(HEART, Items.item(owner ? Material.BEACON : Material.GRAY_DYE, text("menu.heart.name"),
                List.of(text(owner ? "menu.heart.lore" : "menu.owner-only", "price", Money.format(gui.land().rules().heartMovePrice()),
                        "days", String.valueOf(gui.land().rules().heartMoveDays())))));
        inventory.setItem(RULES, Items.item(Material.WRITABLE_BOOK, text("menu.rules.name"), List.of(text("menu.rules.lore"))));
        inventory.setItem(JOURNAL, Items.item(Material.BOOK, text("menu.journal.name"), List.of(text("menu.journal.lore"))));
        inventory.setItem(ABANDON, Items.item(owner ? Material.TNT : Material.GRAY_DYE, text("menu.abandon.name"),
                List.of(text(owner ? "menu.abandon.lore" : "menu.owner-only", "refund", String.valueOf(Math.round(gui.land().pricing().refund() * 100))))));
        inventory.setItem(BACK, Items.item(Material.OAK_DOOR, text("menu.back-to-clan"), List.of()));
    }

    private ItemStack button(Material icon, String key, ClanPermission permission) {
        boolean can = clan.can(viewer.getUniqueId(), permission);
        return Items.item(can ? icon : Material.GRAY_DYE, text(key + ".name"),
                List.of(text(can ? key + ".lore" : "menu.locked")));
    }

    /** Le refus en clair, avec ses valeurs (avant-postes, taille, zone tampon). */
    private String refusal(String key) {
        return messages.plain(viewer, key, "max", String.valueOf(gui.land().maxOutposts(clan)),
                "size", String.valueOf(gui.land().rules().outpostSize()), "buffer", String.valueOf(gui.land().rules().buffer()),
                "price", "-");
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
