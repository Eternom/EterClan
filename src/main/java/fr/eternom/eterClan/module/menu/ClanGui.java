package fr.eternom.eterClan.module.menu;

import fr.eternom.eterClan.module.bank.BankService;
import fr.eternom.eterClan.module.claim.Access;
import fr.eternom.eterClan.module.claim.AccessService;
import fr.eternom.eterClan.module.clan.Journal;
import fr.eternom.eterClan.module.claim.ClaimService;
import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanService;
import fr.eternom.eterClan.module.ranking.ClanLeague;
import fr.eternom.eterClan.module.sync.ClanSync;
import fr.eternom.eterClan.module.zone.Zone;
import fr.eternom.eterClan.module.zone.ZoneSelection;
import fr.eternom.eterClan.module.zone.ZoneService;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Dialogs;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.player.PlayerDirectory;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Toute l'interface des clans (la seule commande est /clan) : le clan, ses membres et leurs permissions, ses parcelles.
 * Les saisies (montant, nom, pseudo) et les confirmations passent par des fenêtres (Dialogs) : Annuler rouvre le menu
 * d'où l'on vient. Chaque action passe par son service, puis le menu se rouvre à jour.
 */
public class ClanGui {

    /** Les cases d'une liste (menus de 54) : 4 lignes de 7, dans le cadre. */
    static final List<Integer> LIST = java.util.stream.IntStream.rangeClosed(1, 4)
            .flatMap(row -> java.util.stream.IntStream.rangeClosed(row * 9 + 1, row * 9 + 7)).boxed().toList();

    private final JavaPlugin plugin;
    private final ClanSync sync;
    private final ClanService clans;
    private final BankService bank;
    private final ClaimService land;
    private final ZoneService zones;
    private final ZoneSelection selection;
    private final Messages messages;
    private final BackButton back;
    private final ClanLeague league;
    private final Access access;
    private final AccessService accessService;
    private final Journal journal;

    public ClanGui(JavaPlugin plugin, ClanSync sync, ClanService clans, BankService bank, ClaimService land, ZoneService zones,
                   ZoneSelection selection, Messages messages, BackButton back, ClanLeague league, Access access,
                   AccessService accessService, Journal journal) {
        this.accessService = accessService;
        this.journal = journal;
        this.league = league;
        this.access = access;
        this.plugin = plugin;
        this.sync = sync;
        this.clans = clans;
        this.bank = bank;
        this.land = land;
        this.zones = zones;
        this.selection = selection;
        this.messages = messages;
        this.back = back;
    }

    // ---------- Menus ----------

    /** Le menu du clan, ou celui pour en créer un (sans clan). */
    public void open(Player player) {
        sync.cache().of(player.getUniqueId()).ifPresentOrElse(
                clan -> player.openInventory(new ClanMenu(this, player, clan).getInventory()),
                () -> player.openInventory(new NoClanMenu(this, player).getInventory()));
    }

    /** La banque : le temps de jeu qui manque pour donner à la réserve est lu d'abord (affiché sur le bouton). */
    void openBank(Player player) {
        clans.clanOf(player).ifPresent(clan -> Tasks.async(plugin, player, () -> bank.giveWait(player.getUniqueId()),
                wait -> player.openInventory(new BankMenu(this, player, clan, wait).getInventory()),
                () -> messages.send(player, "error.generic")));
    }

    void openLand(Player player) {
        clans.clanOf(player).ifPresent(clan -> player.openInventory(new LandMenu(this, player, clan).getInventory()));
    }

    /** La carte du terrain autour du joueur ; selected : les chunks choisis pour être pris d'un coup. */
    void openMap(Player player, java.util.Set<fr.eternom.eterClan.module.claim.LandPlan.Plot> selected) {
        clans.clanOf(player).ifPresent(clan -> player.openInventory(new MapMenu(this, player, clan, selected).getInventory()));
    }

    void openAccess(Player player) {
        clans.clanOf(player).ifPresent(clan -> player.openInventory(new AccessMenu(this, player, clan).getInventory()));
    }

    void openPasses(Player player) {
        clans.clanOf(player).ifPresent(clan -> player.openInventory(new PassesMenu(this, player, clan).getInventory()));
    }

    /** Le journal du clan (CLAN) ou de la réserve (BANK) : les 15 dernières lignes, dans le chat. */
    void showJournal(Player player, Journal.Category category) {
        clans.clanOf(player).ifPresent(clan -> {
            player.closeInventory();
            Tasks.async(plugin, player, () -> journal.last(clan.id(), category, 15), entries -> {
                messages.send(player, entries.isEmpty() ? "journal.empty" : "journal.header-" + category.name().toLowerCase());
                java.time.format.DateTimeFormatter date = java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")
                        .withZone(java.time.ZoneId.systemDefault());
                for (Journal.Entry entry : entries.reversed()) {
                    String[] values = new String[entry.values().size() * 2 + 4];
                    values[0] = "actor";
                    values[1] = entry.actor().isEmpty() ? messages.plain(player, "journal.system") : entry.actor();
                    values[2] = "date";
                    values[3] = date.format(java.time.Instant.ofEpochMilli(entry.createdAt()));
                    for (int i = 0; i < entry.values().size(); i++) {
                        values[4 + i * 2] = "v" + i;
                        values[5 + i * 2] = entry.values().get(i);
                    }
                    messages.send(player, "journal.line", "date", values[3], "text",
                            messages.plain(player, "journal." + entry.key(), values));
                }
            }, () -> messages.send(player, "error.generic"));
        });
    }

    void openTagColor(Player player) {
        clans.clanOf(player).ifPresent(clan -> player.openInventory(new TagColorMenu(this, player, clan).getInventory()));
    }

    void openRules(Player player) {
        clans.clanOf(player).ifPresent(clan -> player.openInventory(new RulesMenu(this, player, clan).getInventory()));
    }

    void openLeague(Player player) {
        clans.clanOf(player).ifPresent(clan -> player.openInventory(new LeagueMenu(this, player, clan).getInventory()));
    }

    void openMembers(Player player) {
        clans.clanOf(player).ifPresent(clan -> player.openInventory(new MembersMenu(this, player, clan).getInventory()));
    }

    void openMember(Player player, UUID member) {
        clans.clanOf(player).ifPresent(clan -> clan.member(member).ifPresentOrElse(
                target -> player.openInventory(new MemberMenu(this, player, clan, target).getInventory()),
                () -> openMembers(player)));
    }

    void openPermissions(Player player, UUID member) {
        clans.clanOf(player).ifPresent(clan -> clan.member(member).ifPresent(target ->
                player.openInventory(new PermissionsMenu(this, player, clan, target).getInventory())));
    }

    void openZones(Player player) {
        clans.clanOf(player).ifPresent(clan -> player.openInventory(new ZonesMenu(this, player, clan).getInventory()));
    }

    void openZone(Player player, long zoneId) {
        clans.clanOf(player).ifPresent(clan -> zone(zoneId).ifPresentOrElse(
                zone -> player.openInventory(new ZoneMenu(this, player, clan, zone).getInventory()),
                () -> openZones(player)));
    }

    /** Les noms des joueurs autorisés sont lus dans l'annuaire du réseau (EterLib) : un joueur jamais venu ici a son nom. */
    void openTrust(Player player, long zoneId) {
        zone(zoneId).ifPresentOrElse(zone -> Tasks.async(plugin, player, () -> {
            Map<UUID, String> names = new LinkedHashMap<>();
            zone.trusted().forEach(uuid -> names.put(uuid, EterLib.get().getPlayers().get(uuid)
                    .map(PlayerDirectory.NetworkPlayer::name).orElse("?")));
            return names;
        }, names -> player.openInventory(new TrustMenu(this, player, zone, names).getInventory()),
                () -> messages.send(player, "error.generic")), () -> openZones(player));
    }

    /** Nouvelle parcelle : deux coins cliqués dans le monde, puis le nom ; Annuler rouvre la liste des parcelles. */
    void newZone(Player player) {
        selection.start(player, (first, second) -> askText(player, "zone-name", "",
                name -> zones.create(player, name, first, second, () -> openZones(player)), () -> openZones(player)),
                () -> openZones(player));
    }

    // ---------- Fenêtres ----------

    /**
     * Fenêtre de création : nom et tag (préremplis depuis /clan create), et le prix affiché AVANT de payer ; Valider crée
     * le clan et paie.
     */
    public void askCreate(Player player, String name, String tag) {
        player.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(player, "create.title"))
                .body(List.of(DialogBody.plainMessage(messages.get(player, "create.body",
                        "price", Money.format(clans.creationPrice())))))
                .inputs(List.of(
                        DialogInput.text("name", messages.get(player, "create.name")).initial(name).maxLength(24).build(),
                        DialogInput.text("tag", messages.get(player, "create.tag")).initial(tag).maxLength(5).build()))
                .build();
        Dialogs.show(plugin, player, base, messages.get(player, "create.confirm", "price", Money.format(clans.creationPrice())),
                messages.get(player, "dialog.cancel"),
                response -> clans.create(player, text(response.getText("name")), text(response.getText("tag"))),
                () -> open(player));
    }

    /** Saisie d'un montant (ou d'un taux) : dialogs.<key>.title / .body ; Annuler rouvre onCancel. */
    void askAmount(Player player, String key, DoubleConsumer action, Runnable onCancel) {
        player.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(player, "dialogs." + key + ".title"))
                .body(List.of(DialogBody.plainMessage(messages.get(player, "dialogs." + key + ".body",
                        "max", BankService.percent(bank.maxInterest())))))
                .inputs(List.of(DialogInput.text("value", messages.get(player, "dialogs.amount")).maxLength(12).build()))
                .build();
        Dialogs.show(plugin, player, base, messages.get(player, "dialogs.confirm"), messages.get(player, "dialog.cancel"),
                response -> action.accept(number(response.getText("value"))), onCancel);
    }

    /** Saisie d'un texte (nom, pseudo) : dialogs.<key>.title / .body / .label. */
    void askText(Player player, String key, String initial, Consumer<String> action, Runnable onCancel) {
        player.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(player, "dialogs." + key + ".title"))
                .body(List.of(DialogBody.plainMessage(messages.get(player, "dialogs." + key + ".body"))))
                .inputs(List.of(DialogInput.text("value", messages.get(player, "dialogs." + key + ".label"))
                        .initial(initial).maxLength(32).build()))
                .build();
        Dialogs.show(plugin, player, base, messages.get(player, "dialogs.confirm"), messages.get(player, "dialog.cancel"),
                response -> action.accept(text(response.getText("value"))), onCancel);
    }

    /** Confirmation (confirm.<key>.title / .body / .button) ; Annuler rouvre onCancel. */
    void confirm(Player player, String key, Runnable action, Runnable onCancel, String... placeholders) {
        player.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(player, "confirm." + key + ".title", placeholders))
                .body(List.of(DialogBody.plainMessage(messages.get(player, "confirm." + key + ".body", placeholders))))
                .build();
        Dialogs.show(plugin, player, base, messages.get(player, "confirm." + key + ".button", placeholders),
                messages.get(player, "dialog.cancel"), response -> action.run(), onCancel);
    }

    // ---------- Accès pour les menus ----------

    Clan refreshed(Clan clan) {
        return sync.cache().get(clan.id()).orElse(clan);
    }

    Optional<Zone> zone(long id) {
        return sync.zones().byId(id);
    }

    List<Zone> zonesOf(Clan clan) {
        return sync.zones().ofClan(clan.id());
    }

    ClanSync sync() {
        return sync;
    }

    ClanLeague league() {
        return league;
    }

    Access access() {
        return access;
    }

    AccessService accessService() {
        return accessService;
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

    ZoneService zones() {
        return zones;
    }

    Messages messages() {
        return messages;
    }

    BackButton back() {
        return back;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
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
