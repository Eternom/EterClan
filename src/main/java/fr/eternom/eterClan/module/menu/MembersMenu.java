package fr.eternom.eterClan.module.menu;

import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.Clan.Member;
import fr.eternom.eterClan.module.clan.ClanPermission;
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

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Les membres (28 au plus affichés) : tête, chef ou nombre de permissions, compte en banque ; clic : sa fiche
 * (permissions, exclure, céder le clan). Le chef d'abord, puis par date d'arrivée. Bouton : inviter (INVITE).
 */
class MembersMenu implements Menu {

    private static final List<Integer> SLOTS = IntStream.rangeClosed(1, 4)
            .flatMap(row -> IntStream.rangeClosed(row * 9 + 1, row * 9 + 7)).boxed().toList();
    private static final int INVITE = 47;
    private static final int BACK = 49;

    private final ClanGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Clan clan;
    private final Inventory inventory;
    private final Map<Integer, UUID> memberAt = new HashMap<>();

    MembersMenu(ClanGui gui, Player viewer, Clan clan) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.clan = clan;
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "members.title", "clan", clan.name()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        UUID member = memberAt.get(slot);
        if (member != null) {
            Sounds.page(player);
            gui.openMember(player, member);
        } else if (slot == INVITE) {
            if (!clan.can(player.getUniqueId(), ClanPermission.INVITE)) {
                messages.send(player, "clan.no-permission");
                return;
            }
            Sounds.click(player);
            gui.askText(player, "invite", "", name -> {
                gui.clans().invite(player, name);
                gui.openMembers(player);
            }, () -> gui.openMembers(player));
        } else if (slot == BACK) {
            Sounds.page(player);
            gui.open(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        List<Member> members = clan.members().stream()
                .sorted(Comparator.comparing((Member member) -> !clan.isOwner(member.uuid())).thenComparingLong(Member::joinedAt))
                .toList();
        for (int i = 0; i < members.size() && i < SLOTS.size(); i++) {
            Member member = members.get(i);
            memberAt.put(SLOTS.get(i), member.uuid());
            String role = clan.isOwner(member.uuid()) ? messages.plain(viewer, "members.owner")
                    : messages.plain(viewer, "members.permissions", "count", String.valueOf(member.permissions().size()));
            inventory.setItem(SLOTS.get(i), Items.head(Bukkit.createProfile(member.uuid(), member.name()),
                    messages.get(viewer, "members.name", "player", member.name()), List.of(
                            messages.get(viewer, "members.role", "role", role),
                            messages.get(viewer, "members.account", "amount", Money.format(member.account())),
                            messages.get(viewer, "members.click"))));
        }
        boolean canInvite = clan.can(viewer.getUniqueId(), ClanPermission.INVITE);
        inventory.setItem(INVITE, Items.item(canInvite ? Material.WRITABLE_BOOK : Material.GRAY_DYE,
                messages.get(viewer, "members.invite.name"),
                List.of(messages.get(viewer, canInvite ? "members.invite.lore" : "menu.locked"))));
        inventory.setItem(BACK, Items.item(Material.ARROW, messages.get(viewer, "menu.back-to-clan"), List.of()));
    }
}
