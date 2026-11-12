package fr.eternom.eterClan.module.menu;

import fr.eternom.eterClan.module.clan.ClanRepository.Recruiting;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * /clan pour un joueur sans clan, 5 lignes :
 * <pre>
 *  ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣
 *  ▣ ▣ ? ▣ + ▣ ✉ ▣ ▣     ? = ce qu'est un clan · + = le créer (nom, tag, prix avant de payer) · ✉ = accepter une invitation
 *  ▣ r r r r r r r ▣     r = les clans qui recrutent (leur annonce) : clic, puis confirmer, pour les rejoindre
 *  ▣ r r r r r r r ▣
 *  ▣ ▣ ▣ ▣ « ▣ ▣ ▣ ▣
 * </pre>
 */
class NoClanMenu implements Menu {

    private static final int INFO = 11;
    private static final int CREATE = 13;
    private static final int ACCEPT = 15;
    private static final List<Integer> SLOTS = IntStream.rangeClosed(2, 3)
            .flatMap(row -> IntStream.rangeClosed(row * 9 + 1, row * 9 + 7)).boxed().toList();
    private static final int BACK = 40;

    private final ClanGui gui;
    private final Messages messages;
    private final Player viewer;
    private final List<Recruiting> recruiting;
    private final Inventory inventory;
    private final Map<Integer, Recruiting> clanAt = new HashMap<>();

    NoClanMenu(ClanGui gui, Player viewer, List<Recruiting> recruiting) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.recruiting = recruiting;
        this.inventory = Bukkit.createInventory(this, 45, messages.get(viewer, "no-clan.title"));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Recruiting clan = clanAt.get(slot);
        if (clan != null) {
            Sounds.click(player);
            gui.confirm(player, "join", () -> gui.clans().joinRecruiting(player, clan.id()), () -> gui.open(player),
                    "clan", clan.name());
            return;
        }
        switch (slot) {
            case CREATE -> {
                Sounds.click(player);
                gui.askCreate(player, "", "");
            }
            case ACCEPT -> {
                Sounds.click(player);
                player.closeInventory();
                gui.clans().accept(player);
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

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        inventory.setItem(INFO, Items.item(Material.BOOK, messages.get(viewer, "no-clan.info.name"), List.of(
                messages.get(viewer, "no-clan.info.line1"), messages.get(viewer, "no-clan.info.line2"),
                messages.get(viewer, "no-clan.info.line3"), messages.get(viewer, "no-clan.info.line4"))));
        inventory.setItem(CREATE, Items.item(Material.WHITE_BANNER, messages.get(viewer, "no-clan.create.name"), List.of(
                messages.get(viewer, "no-clan.create.price", "price", Money.format(gui.clans().creationPrice())),
                messages.get(viewer, "no-clan.create.click")), true));
        inventory.setItem(ACCEPT, Items.item(Material.WRITABLE_BOOK, messages.get(viewer, "no-clan.accept.name"),
                List.of(messages.get(viewer, "no-clan.accept.lore"))));
        if (recruiting.isEmpty()) {
            inventory.setItem(SLOTS.get(3), Items.item(Material.GRAY_BANNER, messages.get(viewer, "no-clan.recruit.none"),
                    List.of(messages.get(viewer, "no-clan.recruit.none-lore"))));
        }
        for (int i = 0; i < recruiting.size() && i < SLOTS.size(); i++) {
            Recruiting clan = recruiting.get(i);
            clanAt.put(SLOTS.get(i), clan);
            // Le tag dans la couleur du clan (lettres et chiffres seulement) ; nom et annonce restent du texte brut
            Component tag = messages.render("<gray>[" + clan.color().paint(clan.tag()) + "]</gray>", TagResolver.empty());
            inventory.setItem(SLOTS.get(i), Items.item(clan.color().icon(), messages.get(viewer, "no-clan.recruit.name",
                    Placeholder.component("tag", tag), "clan", clan.name()), List.of(
                    messages.get(viewer, "no-clan.recruit.ad", "text", clan.text()),
                    messages.get(viewer, "no-clan.recruit.members", "count", String.valueOf(clan.members())),
                    Component.empty(),
                    messages.get(viewer, "no-clan.recruit.click"))));
        }
        inventory.setItem(BACK, gui.back().item(viewer));
    }
}
