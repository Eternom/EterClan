package fr.eternom.eterClan.module.menu;

import fr.eternom.eterClan.module.claim.ClaimIndex.ChunkKey;
import fr.eternom.eterClan.module.claim.ClaimService.Quote;
import fr.eternom.eterClan.module.claim.LandPlan;
import fr.eternom.eterClan.module.claim.LandPlan.Kind;
import fr.eternom.eterClan.module.claim.LandPlan.Plot;
import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanPermission;
import fr.eternom.eterLib.helper.economy.Money;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * La carte du terrain : 9 × 5 chunks autour du joueur (le sien au centre, le nord en haut). Or : le cœur ; vert : la
 * base ; bleu : un avant-poste ; rouge : un autre clan ; gris : libre ; jaune : choisi. Sur un chunk libre : clic gauche,
 * le choisir (ou non) ; le prix de chacun tient compte des chunks déjà choisis. En bas : prendre la sélection (prix
 * total), l'effacer. Sur un chunk du clan : clic droit, le rendre.
 */
class MapMenu implements Menu {

    private static final int COLUMNS = 9;
    private static final int ROWS = 5;
    private static final int TAKE = 47;
    private static final int CLEAR = 48;
    private static final int BACK = 49;
    private static final int LEGEND = 51;

    private final ClanGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Clan clan;
    private final Set<Plot> selected;
    private final Inventory inventory;
    private final Map<Integer, Plot> plotAt = new HashMap<>();
    private double total;

    MapMenu(ClanGui gui, Player viewer, Clan clan, Set<Plot> selected) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.clan = clan;
        this.selected = new LinkedHashSet<>(selected);
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "map.title", "clan", clan.name()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Plot plot = plotAt.get(slot);
        boolean canClaim = gui.refreshed(clan).can(player.getUniqueId(), ClanPermission.CLAIM);
        if (plot != null) {
            boolean own = clan.land().plots().contains(plot);
            if (own && click.isRightClick()) {
                if (!canClaim) {
                    refuse(player);
                    return;
                }
                gui.confirm(player, "unclaim", () -> gui.land().unclaim(player, plot, () -> gui.openMap(player, selected)),
                        () -> gui.openMap(player, selected));
                return;
            }
            if (!own) {
                if (!canClaim) {
                    refuse(player);
                    return;
                }
                Sounds.click(player);
                if (!selected.remove(plot)) {
                    selected.add(plot);
                }
                gui.openMap(player, selected);
            }
            return;
        }
        switch (slot) {
            case TAKE -> {
                if (selected.isEmpty() || !canClaim) {
                    refuse(player);
                    return;
                }
                List<Plot> wanted = new ArrayList<>(selected);
                gui.confirm(player, "claim-many", () -> gui.land().claim(player, wanted, () -> gui.openMap(player, Set.of())),
                        () -> gui.openMap(player, selected), "count", String.valueOf(wanted.size()), "price", Money.format(total));
            }
            case CLEAR -> {
                Sounds.click(player);
                gui.openMap(player, Set.of());
            }
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

    private void refuse(Player player) {
        Sounds.refuse(player);
        messages.send(player, "clan.no-permission");
    }

    private void render() {
        ChunkKey center = ChunkKey.of(viewer.getLocation());
        Set<Plot> owned = new HashSet<>(clan.land().plots());
        Plot heart = clan.land().heart();
        // Les chunks choisis, dans l'ordre : chacun est chiffré comme s'il s'ajoutait aux précédents
        Map<Plot, Quote> quotes = new HashMap<>();
        Set<Plot> simulated = new HashSet<>(owned);
        Plot simulatedHeart = heart;
        total = 0;
        for (Plot plot : selected) {
            Quote quote = gui.land().quote(clan, plot, simulated, simulatedHeart);
            quotes.put(plot, quote);
            if (quote.allowed()) {
                if (simulated.isEmpty()) {
                    simulatedHeart = plot;
                }
                simulated.add(plot);
                total += quote.price();
            }
        }
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                int slot = row * COLUMNS + column;
                ChunkKey chunk = center.relative(column - COLUMNS / 2, row - ROWS / 2);
                Plot plot = gui.land().plot(chunk);
                plotAt.put(slot, plot);
                inventory.setItem(slot, cell(chunk, plot, owned, heart, quotes, simulated, simulatedHeart,
                        chunk.equals(center)));
            }
        }
        boolean any = !selected.isEmpty();
        inventory.setItem(TAKE, Items.item(any ? Material.LIME_CONCRETE : Material.GRAY_DYE, text("map.take.name",
                "count", String.valueOf(selected.size())), List.of(text("map.take.lore", "price", Money.format(total)))));
        inventory.setItem(CLEAR, Items.item(Material.BUCKET, text("map.clear"), List.of()));
        inventory.setItem(BACK, Items.item(Material.OAK_DOOR, text("menu.back-to-clan"), List.of()));
        inventory.setItem(LEGEND, Items.item(Material.BOOK, text("map.legend.name"), List.of(text("map.legend.core"),
                text("map.legend.base"), text("map.legend.outpost"), text("map.legend.other"), text("map.legend.free"),
                text("map.legend.clicks"))));
    }

    private org.bukkit.inventory.ItemStack cell(ChunkKey chunk, Plot plot, Set<Plot> owned, Plot heart, Map<Plot, Quote> quotes,
                                                Set<Plot> simulated, Plot simulatedHeart, boolean here) {
        List<Component> lore = new ArrayList<>();
        lore.add(text("map.coords", "x", String.valueOf(chunk.x() << 4), "z", String.valueOf(chunk.z() << 4)));
        if (here) {
            lore.add(text("map.you-are-here"));
        }
        Material icon;
        Component name;
        OptionalLong owner = gui.sync().claims().owner(chunk);
        if (owned.contains(plot)) {
            Kind kind = LandPlan.kindOf(heart, owned, plot);
            icon = kind == Kind.CORE ? Material.GOLD_BLOCK : kind == Kind.EXTENSION ? Material.LIME_STAINED_GLASS : Material.LIGHT_BLUE_STAINED_GLASS;
            name = text("map.own", "kind", messages.plain(viewer, "kind." + kind.name().toLowerCase()));
            lore.add(text(kind == Kind.CORE ? "map.core-locked" : "map.click-unclaim"));
        } else if (owner.isPresent()) {
            icon = Material.RED_STAINED_GLASS;
            name = text("map.other", "clan", gui.sync().cache().get(owner.getAsLong()).map(Clan::name).orElse("?"));
        } else if (selected.contains(plot)) {
            Quote quote = quotes.get(plot);
            icon = quote != null && quote.allowed() ? Material.YELLOW_STAINED_GLASS : Material.ORANGE_STAINED_GLASS;
            name = text("map.selected");
            if (quote != null && quote.allowed()) {
                lore.add(text("map.price", "kind", messages.plain(viewer, "kind." + quote.kind().name().toLowerCase()),
                        "price", Money.format(quote.price())));
            } else if (quote != null) {
                lore.add(text("map.refused", "reason", refusal(quote.refusal())));
            }
            lore.add(text("map.click-unselect"));
        } else {
            Quote quote = gui.land().quote(clan, plot, simulated, simulatedHeart);
            icon = quote.allowed() ? Material.LIGHT_GRAY_STAINED_GLASS : Material.GRAY_STAINED_GLASS;
            name = text("map.free");
            if (quote.allowed()) {
                lore.add(text("map.price", "kind", messages.plain(viewer, "kind." + quote.kind().name().toLowerCase()),
                        "price", Money.format(quote.price())));
                lore.add(text("map.click-select"));
            } else {
                lore.add(text("map.refused", "reason", refusal(quote.refusal())));
            }
        }
        if (here) {
            icon = icon == Material.LIGHT_GRAY_STAINED_GLASS || icon == Material.GRAY_STAINED_GLASS ? Material.COMPASS : icon;
        }
        return Items.item(icon, name, lore, here);
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
