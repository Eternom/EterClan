package fr.eternom.eterClan.module.zone;

import fr.eternom.eterClan.module.zone.ZoneService.Box;
import fr.eternom.eterLib.helper.message.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Tracer une parcelle sans commande : après le bouton « Nouvelle parcelle », le joueur clique deux blocs (les coins ;
 * le clic ne casse ni n'utilise rien). Le rectangle s'affiche en particules, puis des boutons dans le chat : Valider
 * (le menu demande le nom), Recommencer, Annuler (retour au menu). Abandonné après 3 minutes ou à la déconnexion.
 */
public class ZoneSelection implements Listener {

    private static final Duration TIMEOUT = Duration.ofMinutes(3);
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder().uses(1).lifetime(TIMEOUT).build();

    /** Une sélection en cours : les coins, ce qu'on fait au bout, et l'aperçu en particules. */
    private static final class Selection {
        Location first;
        Location second;
        final BiConsumer<Location, Location> onConfirm;
        final Runnable onCancel;
        final long startedAt = System.currentTimeMillis();
        BukkitTask preview;

        Selection(BiConsumer<Location, Location> onConfirm, Runnable onCancel) {
            this.onConfirm = onConfirm;
            this.onCancel = onCancel;
        }
    }

    private final JavaPlugin plugin;
    private final Messages messages;
    private final ZoneService zones;
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

    public ZoneSelection(JavaPlugin plugin, Messages messages, ZoneService zones) {
        this.plugin = plugin;
        this.messages = messages;
        this.zones = zones;
    }

    public void start(Player player, BiConsumer<Location, Location> onConfirm, Runnable onCancel) {
        stop(player.getUniqueId());
        Selection selection = new Selection(onConfirm, onCancel);
        selections.put(player.getUniqueId(), selection);
        selection.preview = Bukkit.getScheduler().runTaskTimer(plugin, () -> preview(player, selection), 0, 10);
        player.closeInventory();
        player.sendMessage(messages.prefix().append(messages.get(player, "selection.start",
                "max", String.valueOf(zones.maxArea()))).append(Component.space()).append(cancelButton(player)));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(PlayerInteractEvent event) {
        Selection selection = selections.get(event.getPlayer().getUniqueId());
        Block block = event.getClickedBlock();
        if (selection == null || block == null || event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        event.setCancelled(true); // un coin, pas un bloc cassé ni un coffre ouvert
        Player player = event.getPlayer();
        player.playSound(player, Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.4f);
        if (selection.first == null || selection.second != null) {
            selection.first = block.getLocation();
            selection.second = null;
            messages.send(player, "selection.first", "x", String.valueOf(block.getX()), "z", String.valueOf(block.getZ()));
            return;
        }
        selection.second = block.getLocation();
        if (!zones.checkArea(player, selection.first, selection.second)) {
            selection.second = null; // message déjà donné : on garde le premier coin
            return;
        }
        Box box = Box.of(selection.first, selection.second);
        player.sendMessage(messages.prefix().append(messages.get(player, "selection.done",
                        "width", String.valueOf(box.width()), "depth", String.valueOf(box.depth()),
                        "area", String.valueOf(box.width() * box.depth())))
                .append(Component.space()).append(button(player, "selection.confirm", () -> {
                    Selection current = selections.get(player.getUniqueId());
                    if (current == selection && current.second != null) {
                        stop(player.getUniqueId());
                        selection.onConfirm.accept(selection.first, selection.second);
                    }
                }))
                .append(Component.space()).append(button(player, "selection.restart", () -> {
                    if (selections.get(player.getUniqueId()) == selection) {
                        selection.first = null;
                        selection.second = null;
                        messages.send(player, "selection.restarted");
                    }
                }))
                .append(Component.space()).append(cancelButton(player)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stop(event.getPlayer().getUniqueId());
    }

    private Component cancelButton(Player player) {
        return button(player, "selection.cancel", () -> {
            Selection selection = selections.get(player.getUniqueId());
            if (selection != null) {
                stop(player.getUniqueId());
                messages.send(player, "selection.cancelled");
                selection.onCancel.run();
            }
        });
    }

    /** Bouton cliquable du chat (une fois), traité sur le thread principal si le joueur est encore là. */
    private Component button(Player player, String key, Runnable action) {
        return messages.get(player, key).clickEvent(ClickEvent.callback(audience -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                action.run();
            }
        }), ONCE));
    }

    private void stop(UUID player) {
        Selection selection = selections.remove(player);
        if (selection != null && selection.preview != null) {
            selection.preview.cancel();
        }
    }

    /** Toutes les demi-secondes : le rectangle (ou le premier coin) en particules ; abandon après le délai. */
    private void preview(Player player, Selection selection) {
        if (!player.isOnline() || System.currentTimeMillis() - selection.startedAt > TIMEOUT.toMillis()) {
            if (selections.get(player.getUniqueId()) == selection) {
                stop(player.getUniqueId());
                if (player.isOnline()) {
                    messages.send(player, "selection.expired");
                }
            }
            return;
        }
        if (selection.first == null || selection.first.getWorld() != player.getWorld()) {
            return;
        }
        Particle.DustOptions dust = new Particle.DustOptions(Color.ORANGE, 1.3f);
        double y = player.getLocation().getY() + 1;
        if (selection.second == null) {
            for (double dy = 0; dy < 3; dy += 0.5) {
                player.spawnParticle(Particle.DUST, selection.first.clone().add(0.5, dy + 1, 0.5), 1, dust);
            }
            return;
        }
        Box box = Box.of(selection.first, selection.second);
        double step = Math.max(1, (box.width() + box.depth()) / 40.0);
        for (double x = box.minX(); x <= box.maxX() + 1; x += step) {
            player.spawnParticle(Particle.DUST, new Location(player.getWorld(), x, y, box.minZ()), 1, dust);
            player.spawnParticle(Particle.DUST, new Location(player.getWorld(), x, y, box.maxZ() + 1), 1, dust);
        }
        for (double z = box.minZ(); z <= box.maxZ() + 1; z += step) {
            player.spawnParticle(Particle.DUST, new Location(player.getWorld(), box.minX(), y, z), 1, dust);
            player.spawnParticle(Particle.DUST, new Location(player.getWorld(), box.maxX() + 1, y, z), 1, dust);
        }
    }
}
