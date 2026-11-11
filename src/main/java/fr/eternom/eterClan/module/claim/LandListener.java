package fr.eternom.eterClan.module.claim;

import fr.eternom.eterClan.module.claim.ClaimIndex.ChunkKey;
import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanPermission;
import fr.eternom.eterClan.module.sync.ClanSync;
import fr.eternom.eterClan.module.zone.Zone;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.Dialogs;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.List;
import fr.eternom.eterLib.helper.message.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Où l'on est, et où l'on peut aller :
 * - en changeant de terrain ou de parcelle : un titre (le clan, la parcelle, ouvert / fermé / à pass) et l'action bar ;
 * - un terrain ou une parcelle fermés, à pass, ou la liste noire : on n'y entre pas (repoussé au bord, avec le bouton
 *   pour acheter le pass s'il y en a un) ; aucune téléportation n'y mène (perle, fruit de chorus, tpa...), sauf pour le
 *   staff. Quelqu'un déjà dedans (connecté là) peut en sortir ;
 * - un panneau « [Pass] » posé par un membre qui a ACCESS (ou ZONES dans une parcelle) devient un guichet : clic droit
 *   pour acheter le pass.
 * Connexion : le clan du joueur passe en mémoire (ClanSync).
 */
public class LandListener implements Listener {

    private static final String SIGN_TAG = "[pass]";
    private static final java.util.Set<String> RENT_TAGS = java.util.Set.of("[louer]", "[rent]");
    private static final long WARN_MILLIS = 2_000;

    private final ClanSync sync;
    private final Messages messages;
    private final Access access;
    private final AccessService accessService;
    private final fr.eternom.eterClan.module.zone.ZoneService zoneService;
    /** Pas de terrain sur ce serveur : pas d'annonce en se déplaçant. */
    private final boolean landEnabled;
    /** Dernier endroit annoncé à chaque joueur (clan:parcelle), pour ne parler que quand il change. */
    private final Map<UUID, String> last = new ConcurrentHashMap<>();
    private final Map<UUID, Long> warned = new ConcurrentHashMap<>();

    public LandListener(ClanSync sync, Messages messages, Access access, AccessService accessService,
                        fr.eternom.eterClan.module.zone.ZoneService zoneService, boolean landEnabled) {
        this.zoneService = zoneService;
        this.sync = sync;
        this.messages = messages;
        this.access = access;
        this.accessService = accessService;
        this.landEnabled = landEnabled;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        sync.join(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        last.remove(event.getPlayer().getUniqueId());
        warned.remove(event.getPlayer().getUniqueId());
    }

    /** Entrer dans un endroit interdit : repoussé (on peut toujours sortir d'un endroit où l'on est déjà). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnter(PlayerMoveEvent event) {
        if (!landEnabled || sameBlock(event.getFrom(), event.getTo())) {
            return;
        }
        Player player = event.getPlayer();
        Optional<String> refusal = access.refusal(player, event.getTo());
        if (refusal.isEmpty() || access.refusal(player, event.getFrom()).isPresent()) {
            return;
        }
        Location back = event.getFrom().clone();
        back.setYaw(event.getTo().getYaw());
        back.setPitch(event.getTo().getPitch());
        event.setTo(back);
        warn(player, refusal.get(), event.getTo());
    }

    /** Aucune téléportation vers un endroit interdit (perle, fruit de chorus, tpa, home...). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!landEnabled || event.getTo() == null) {
            return;
        }
        Optional<String> refusal = access.refusal(event.getPlayer(), event.getTo());
        if (refusal.isPresent()) {
            event.setCancelled(true);
            warn(event.getPlayer(), refusal.get(), event.getTo());
        }
    }

    /** Le refus, une fois toutes les 2 s au plus ; avec le bouton d'achat s'il y a un pass à vendre. */
    private void warn(Player player, String key, Location to) {
        long now = System.currentTimeMillis();
        Long previous = warned.get(player.getUniqueId());
        if (previous != null && now - previous < WARN_MILLIS) {
            return;
        }
        warned.put(player.getUniqueId(), now);
        long owner = sync.claims().owner(to).orElse(-1);
        Clan clan = sync.cache().get(owner).orElse(null);
        String name = clan == null ? "?" : clan.name();
        messages.actionBar(player, key, "clan", name);
        if (clan == null || !key.startsWith("access.pass")) {
            return;
        }
        Zone zone = key.equals("access.pass-zone") ? sync.zones().at(to).orElse(null) : null;
        double price = zone == null ? clan.land().offer().price() : zone.passPrice();
        int days = zone == null ? clan.land().offer().days() : zone.passDays();
        if (price <= 0) {
            return;
        }
        Component line = messages.get(player, "access.buy-offer", "clan", name, "price", Money.format(price), "days", String.valueOf(days));
        Component button = messages.get(player, "access.buy-button")
                .clickEvent(ClickEvent.runCommand("/clan pass " + clan.id() + " " + (zone == null ? 0 : zone.id())));
        player.sendMessage(messages.prefix().append(line).append(Component.space()).append(button));
    }

    /** Annonce du lieu : titre et action bar, seulement quand il change. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!landEnabled || sameBlock(event.getFrom(), event.getTo())) {
            return;
        }
        Player player = event.getPlayer();
        long owner = sync.claims().owner(ChunkKey.of(event.getTo())).orElse(-1);
        Zone zone = owner < 0 ? null : sync.zones().at(event.getTo()).filter(found -> found.clanId() == owner).orElse(null);
        String place = owner + ":" + (zone == null ? "" : zone.id());
        String previous = last.put(player.getUniqueId(), place);
        if (place.equals(previous) || (previous == null && owner < 0)) {
            return; // rien de neuf, ou premier pas en nature sauvage après la connexion
        }
        if (owner < 0) {
            messages.actionBar(player, "land.wild");
            return;
        }
        Clan clan = sync.cache().get(owner).orElse(null);
        String name = clan == null ? "?" : clan.name();
        if (zone == null) {
            messages.actionBar(player, "land.clan", "clan", name);
        } else if (zone.isRented()) {
            messages.actionBar(player, "land.zone-rented", "zone", zone.name(), "tenant", zone.tenantName(), "clan", name);
        } else {
            messages.actionBar(player, "land.zone", "zone", zone.name(), "clan", name);
        }
        // Le titre : seulement en arrivant sur le terrain d'un autre clan (pas à chaque parcelle de sa propre base)
        boolean ownLand = clan != null && clan.isMember(player.getUniqueId());
        boolean enteringLand = previous == null || !previous.startsWith(owner + ":");
        if (clan != null && !ownLand && enteringLand) {
            String mode = messages.plain(player, "access.mode." + clan.land().mode().name().toLowerCase(Locale.ROOT));
            player.showTitle(Title.title(messages.get(player, "land.title", "clan", name, "tag", clan.tag()),
                    messages.get(player, "land.subtitle", "mode", mode),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(400))));
        }
    }

    // ---------- Guichets ----------

    /** « [Pass] » sur la première ligne, posé par qui gère l'accès : devient un guichet (prix et durée écrits dessous). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        String first = PlainTextComponentSerializer.plainText().serialize(event.line(0) == null ? Component.empty() : event.line(0));
        if (RENT_TAGS.contains(first.trim().toLowerCase(Locale.ROOT))) {
            rentSign(event);
            return;
        }
        if (!first.trim().equalsIgnoreCase(SIGN_TAG)) {
            return;
        }
        Player player = event.getPlayer();
        Location location = event.getBlock().getLocation();
        Clan clan = sync.claims().owner(location).stream().boxed().findFirst().flatMap(id -> sync.cache().get(id)).orElse(null);
        Zone zone = clan == null ? null : sync.zones().at(location).filter(found -> found.clanId() == clan.id()
                && found.mode() == Zone.Mode.PASS).orElse(null);
        ClanPermission needed = zone == null ? ClanPermission.ACCESS : ClanPermission.ZONES;
        if (clan == null || !clan.can(player.getUniqueId(), needed)) {
            messages.send(player, "access.sign-not-allowed");
            event.setCancelled(true);
            return;
        }
        double price = zone == null ? clan.land().offer().price() : zone.passPrice();
        int days = zone == null ? clan.land().offer().days() : zone.passDays();
        event.line(0, messages.get(player, "access.sign.title"));
        event.line(1, messages.get(player, "access.sign.place", "place", zone == null ? clan.name() : zone.name()));
        event.line(2, messages.get(player, "access.sign.price", "price", Money.format(price)));
        event.line(3, messages.get(player, "access.sign.days", "days", String.valueOf(days)));
        messages.send(player, "access.sign-created");
    }

    /** Clic droit sur un guichet : acheter le pass de cet endroit. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSignClick(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND || block == null
                || !Tag.ALL_SIGNS.isTagged(block.getType()) || !(block.getState(false) instanceof Sign sign)) {
            return;
        }
        String first = PlainTextComponentSerializer.plainText().serialize(sign.getSide(Side.FRONT).line(0));
        String title = PlainTextComponentSerializer.plainText().serialize(messages.get(event.getPlayer(), "access.sign.title"));
        String rentTitle = PlainTextComponentSerializer.plainText().serialize(messages.get(event.getPlayer(), "zone.sign.title"));
        if (first.equalsIgnoreCase(rentTitle)) {
            sync.zones().at(block.getLocation()).ifPresent(zone -> {
                event.setCancelled(true);
                // Même confirmation que le menu des parcelles : un clic droit par erreur ne loue pas
                Player player = event.getPlayer();
                String[] values = {"zone", zone.name(), "rent", Money.format(zone.rent())};
                DialogBase dialog = DialogBase.builder(messages.get(player, "confirm.zone-take.title", values))
                        .body(List.of(DialogBody.plainMessage(messages.get(player, "confirm.zone-take.body", values)))).build();
                Dialogs.show(JavaPlugin.getProvidingPlugin(LandListener.class), player, dialog,
                        messages.get(player, "confirm.zone-take.button", values), messages.get(player, "dialog.cancel"),
                        response -> zoneService.take(player, zone, () -> { }), () -> { });
            });
            return;
        }
        if (!first.equalsIgnoreCase(title)) {
            return;
        }
        Clan clan = sync.claims().owner(block.getLocation()).stream().boxed().findFirst().flatMap(id -> sync.cache().get(id)).orElse(null);
        if (clan == null) {
            return;
        }
        event.setCancelled(true);
        Zone zone = sync.zones().at(block.getLocation()).filter(found -> found.clanId() == clan.id()
                && found.mode() == Zone.Mode.PASS).orElse(null);
        accessService.buy(event.getPlayer(), clan, zone, () -> { });
    }

    /** « [Louer] » dans une parcelle à louer ouverte à tous, posé par qui gère les parcelles : un guichet de location. */
    private void rentSign(SignChangeEvent event) {
        Player player = event.getPlayer();
        Location location = event.getBlock().getLocation();
        Zone zone = sync.zones().at(location).orElse(null);
        Clan clan = zone == null ? null : sync.cache().get(zone.clanId()).orElse(null);
        if (clan == null || !clan.can(player.getUniqueId(), ClanPermission.ZONES) || zone.rent() <= 0 || !zone.publicRent()) {
            messages.send(player, "zone.sign-not-allowed");
            event.setCancelled(true);
            return;
        }
        event.line(0, messages.get(player, "zone.sign.title"));
        event.line(1, messages.get(player, "access.sign.place", "place", zone.name()));
        event.line(2, messages.get(player, "zone.sign.rent", "rent", Money.format(zone.rent())));
        event.line(3, messages.get(player, "zone.sign.week"));
        messages.send(player, "access.sign-created");
    }

    private static boolean sameBlock(Location from, Location to) {
        return from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ() && from.getBlockY() == to.getBlockY()
                && from.getWorld() == to.getWorld();
    }
}
