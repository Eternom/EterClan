package fr.eternom.eterClan.module.menu;

import fr.eternom.eterClan.module.bank.BankService;
import fr.eternom.eterClan.module.claim.ClaimService;
import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanService;
import fr.eternom.eterClan.module.sync.ClanSync;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Dialogs;
import fr.eternom.eterLib.helper.message.Messages;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;
import java.util.function.DoubleConsumer;

/**
 * Les menus de /clan : le clan (infos, banque, terrain), ses membres, et les permissions d'un membre. Les montants et
 * le taux se saisissent dans une fenêtre (Dialog) ; chaque action passe par son service, puis le menu se rouvre à jour.
 */
public class ClanGui {

    private final JavaPlugin plugin;
    private final ClanSync sync;
    private final ClanService clans;
    private final BankService bank;
    private final ClaimService land;
    private final Messages messages;
    private final BackButton back;

    public ClanGui(JavaPlugin plugin, ClanSync sync, ClanService clans, BankService bank, ClaimService land, Messages messages,
                   BackButton back) {
        this.plugin = plugin;
        this.sync = sync;
        this.clans = clans;
        this.bank = bank;
        this.land = land;
        this.messages = messages;
        this.back = back;
    }

    public void open(Player player) {
        clans.clanOf(player).ifPresent(clan -> player.openInventory(new ClanMenu(this, player, clan).getInventory()));
    }

    void openMembers(Player player) {
        clans.clanOf(player).ifPresent(clan -> player.openInventory(new MembersMenu(this, player, clan).getInventory()));
    }

    void openPermissions(Player player, UUID member) {
        clans.clanOf(player).ifPresent(clan -> clan.member(member).ifPresent(target ->
                player.openInventory(new PermissionsMenu(this, player, clan, target).getInventory())));
    }

    /** Fenêtre de saisie d'un montant (ou d'un taux), puis action ; Annuler rouvre le menu du clan. */
    void askAmount(Player player, String key, DoubleConsumer action) {
        player.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(player, key + ".title"))
                .body(List.of(DialogBody.plainMessage(messages.get(player, key + ".body", "max", BankService.percent(bank.maxInterest())))))
                .inputs(List.of(DialogInput.text("amount", messages.get(player, "menu.amount")).maxLength(12).build()))
                .build();
        Dialogs.show(plugin, player, base, messages.get(player, "menu.confirm"), messages.get(player, "dialog.cancel"),
                response -> action.accept(number(response.getText("amount"))), () -> open(player));
    }

    Clan refreshed(Clan clan) {
        return sync.cache().get(clan.id()).orElse(clan);
    }

    ClanService clans() {
        return clans;
    }

    BankService bank() {
        return bank;
    }

    ClaimService land() {
        return land;
    }

    Messages messages() {
        return messages;
    }

    BackButton back() {
        return back;
    }

    /** « 1 250,5 » ou « 1250.5 » ; NaN si illisible (refusé par les services). */
    private static double number(String text) {
        if (text == null) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(text.replace(" ", "").replace(" ", "").replace(',', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
