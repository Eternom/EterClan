package fr.eternom.eterClan.module.menu;

import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.Land.Pass;
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Les passes et invitations en cours (28 au plus) : qui, pour où, jusqu'à quand ; clic : le retirer (ACCESS). */
class PassesMenu implements Menu {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault());
    private static final int BACK = 49;

    private final ClanGui gui;
    private final Clan clan;
    private final Inventory inventory;
    private final Map<Integer, Pass> passAt = new HashMap<>();

    PassesMenu(ClanGui gui, Player viewer, Clan clan) {
        this.gui = gui;
        this.clan = clan;
        Messages messages = gui.messages();
        long now = System.currentTimeMillis();
        List<Pass> passes = clan.land().passes().stream().filter(pass -> pass.isValid(now)).toList();
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "passes.title", "count", String.valueOf(passes.size())));
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        for (int i = 0; i < passes.size() && i < ClanGui.LIST.size(); i++) {
            Pass pass = passes.get(i);
            int slot = ClanGui.LIST.get(i);
            passAt.put(slot, pass);
            String place = pass.zoneId() == 0 ? messages.plain(viewer, "passes.land")
                    : gui.zone(pass.zoneId()).map(zone -> zone.name()).orElse("?");
            inventory.setItem(slot, Items.head(Bukkit.createProfile(pass.player(), pass.name()),
                    messages.get(viewer, pass.guest() ? "passes.guest" : "passes.name", "player", pass.name()), List.of(
                            messages.get(viewer, "passes.place", "place", place),
                            messages.get(viewer, "passes.until", "date", DATE.format(Instant.ofEpochMilli(pass.expiresAt()))),
                            messages.get(viewer, "passes.paid", "price", Money.format(pass.paid())),
                            messages.get(viewer, "passes.click"))));
        }
        if (passes.isEmpty()) {
            inventory.setItem(22, Items.item(Material.LIME_DYE, messages.get(viewer, "passes.empty"), List.of()));
        }
        inventory.setItem(BACK, Items.item(Material.OAK_DOOR, messages.get(viewer, "menu.back-to-clan"), List.of()));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Pass pass = passAt.get(slot);
        if (pass != null) {
            gui.confirm(player, "revoke", () -> gui.accessService().revoke(player, gui.refreshed(clan), pass, () -> gui.openPasses(player)),
                    () -> gui.openPasses(player), "player", pass.name());
        } else if (slot == BACK) {
            Sounds.page(player);
            gui.openAccess(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
