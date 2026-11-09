package fr.eternom.eterClan.module.claim;

import fr.eternom.eterClan.module.clan.ClanPermission;
import fr.eternom.eterClan.module.clan.ClanRule;
import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Monster;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.block.data.type.Dispenser;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le terrain des clans est hors d'atteinte. Joueurs : chaque action demande la permission qui la concerne (Access).
 * Monde : rien de ce qui vient de l'extérieur n'y entre (explosions, feu, pistons, liquides, distributeurs, arbres,
 * monstres qui modifient des blocs), et pas de PvP sur un terrain. Les créatures hostiles restent attaquables partout.
 */
public class ProtectionListener implements Listener {

    /** Blocs qu'un clic droit modifie (hors conteneurs, portes et redstone) : demandent BUILD. */
    private static final Set<Material> EDITABLE = Set.of(Material.FLOWER_POT, Material.CAKE, Material.COMPOSTER,
            Material.LECTERN, Material.JUKEBOX, Material.RESPAWN_ANCHOR, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.CHISELED_BOOKSHELF, Material.DECORATED_POT, Material.BEEHIVE, Material.BEE_NEST, Material.DRAGON_EGG,
            Material.VAULT, Material.SUSPICIOUS_SAND, Material.SUSPICIOUS_GRAVEL);
    private static final Set<Material> REDSTONE = Set.of(Material.LEVER, Material.REPEATER, Material.COMPARATOR,
            Material.DAYLIGHT_DETECTOR, Material.NOTE_BLOCK, Material.TRIPWIRE);
    /** Objets qui transforment le bloc cliqué (labourer, tailler, écorcer, faire pousser, allumer, cirer...). */
    private static final Set<Material> TOOLS = Set.of(Material.BONE_MEAL, Material.FLINT_AND_STEEL, Material.FIRE_CHARGE,
            Material.BRUSH, Material.HONEYCOMB, Material.SHEARS);

    private final Access access;
    private final Messages messages;
    /** Dernier avertissement par joueur : un seul message par seconde au plus, même si l'action se répète. */
    private final ConcurrentHashMap<UUID, Long> warned = new ConcurrentHashMap<>();

    public ProtectionListener(Access access, Messages messages) {
        this.access = access;
        this.messages = messages;
    }

    // ---------- Joueurs : blocs ----------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        deny(event.getPlayer(), event.getBlock().getLocation(), isCrop(event.getBlock()) ? ClanPermission.CROPS : ClanPermission.BUILD, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        deny(event.getPlayer(), placed.getLocation(), isCrop(placed) ? ClanPermission.CROPS : ClanPermission.BUILD, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        deny(event.getPlayer(), event.getBlock().getLocation(), ClanPermission.BUILD, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        deny(event.getPlayer(), event.getBlock().getLocation(), ClanPermission.BUILD, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        deny(event.getPlayer(), event.getBlock().getLocation(), ClanPermission.BUILD, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLectern(PlayerTakeLecternBookEvent event) {
        deny(event.getPlayer(), event.getLectern().getLocation(), ClanPermission.CONTAINERS, event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Player player = event.getPlayer();
        Location location = block.getLocation();
        if (event.getAction() == Action.PHYSICAL) {
            // Piétiner une culture, une plaque de pression, un fil
            ClanPermission permission = block.getType() == Material.FARMLAND ? ClanPermission.CROPS : ClanPermission.REDSTONE;
            if (!access.allows(player, location, permission)) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ClanPermission permission = interaction(block);
        if (permission != null && !access.allows(player, location, permission)) {
            event.setUseInteractedBlock(Event.Result.DENY);
            warn(player, permission);
        }
        ItemStack item = event.getItem();
        if (item != null && changesBlock(item.getType()) && !access.allows(player, location, ClanPermission.BUILD)) {
            event.setUseItemInHand(Event.Result.DENY);
            warn(player, ClanPermission.BUILD);
        }
    }

    // ---------- Joueurs : entités ----------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        if (entity instanceof Villager || entity instanceof Monster || entity instanceof Player) {
            return; // commercer, et rien à protéger
        }
        deny(event.getPlayer(), entity.getLocation(), entity instanceof Animals ? ClanPermission.ANIMALS : ClanPermission.BUILD, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = playerOf(event.getDamager());
        Entity victim = event.getEntity();
        if (attacker == null) {
            return;
        }
        if (victim instanceof Player) {
            // Pas de PvP sur un terrain (sauf si son clan l'autorise), ni pour celui qui y est ni pour celui qui tire depuis
            if (victim != attacker && (access.protects(victim.getLocation(), ClanRule.PVP)
                    || access.protects(attacker.getLocation(), ClanRule.PVP))) {
                event.setCancelled(true);
            }
            return;
        }
        if (victim instanceof Monster) {
            return;
        }
        deny(attacker, victim.getLocation(), victim instanceof Animals ? ClanPermission.ANIMALS : ClanPermission.BUILD, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null) {
            deny(event.getPlayer(), event.getEntity().getLocation(), ClanPermission.BUILD, event);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        Location location = event.getEntity().getLocation();
        if (event instanceof HangingBreakByEntityEvent byEntity && playerOf(byEntity.getRemover()) instanceof Player player) {
            deny(player, location, ClanPermission.BUILD, event);
        } else if (event.getCause() != HangingBreakEvent.RemoveCause.PHYSICS && access.isClaimed(location)) {
            event.setCancelled(true); // explosion, entité : hors d'atteinte
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getPlayer() != null) {
            deny(event.getPlayer(), event.getEntity().getLocation(), ClanPermission.BUILD, event);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        Player player = event.getAttacker() == null ? null : playerOf(event.getAttacker());
        if (player != null) {
            deny(player, event.getVehicle().getLocation(), ClanPermission.BUILD, event);
        }
    }

    // ---------- Le monde : rien n'entre de l'extérieur ----------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> access.protects(block.getLocation(), ClanRule.EXPLOSIONS));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> access.protects(block.getLocation(), ClanRule.EXPLOSIONS));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (access.protects(event.getBlock().getLocation(), ClanRule.FIRE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (event.getSource().getType() == Material.FIRE && access.protects(event.getBlock().getLocation(), ClanRule.FIRE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Location location = event.getBlock().getLocation();
        if (event.getPlayer() != null) {
            deny(event.getPlayer(), location, ClanPermission.BUILD, event);
        } else if (access.protects(location, ClanRule.FIRE)) {
            event.setCancelled(true); // feu qui se propage, lave, foudre, flèche, boule de feu (sauf si le clan l'autorise)
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        Location piston = event.getBlock().getLocation();
        boolean crosses = access.differentOwners(piston, event.getBlock().getRelative(event.getDirection()).getLocation())
                || event.getBlocks().stream().anyMatch(block -> access.differentOwners(piston, block.getLocation())
                || access.differentOwners(piston, block.getRelative(event.getDirection()).getLocation()));
        if (crosses) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        Location piston = event.getBlock().getLocation();
        if (event.getBlocks().stream().anyMatch(block -> access.differentOwners(piston, block.getLocation()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Location to = event.getToBlock().getLocation();
        if (access.isClaimed(to) && access.differentOwners(event.getBlock().getLocation(), to)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (event.getBlock().getBlockData() instanceof Dispenser dispenser) {
            Location target = event.getBlock().getRelative(dispenser.getFacing()).getLocation();
            if (access.isClaimed(target) && access.differentOwners(event.getBlock().getLocation(), target)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent event) {
        Location origin = event.getLocation();
        event.getBlocks().removeIf(state -> access.isClaimed(state.getLocation()) && access.differentOwners(origin, state.getLocation()));
    }

    /** Créatures qui modifient des blocs (endermen, ravageurs, wither, zombies et portes...) : pas sur un terrain. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player player) {
            deny(player, event.getBlock().getLocation(), ClanPermission.BUILD, event);
        } else if (!(entity instanceof FallingBlock || entity instanceof Villager || entity instanceof Sheep)
                && access.isClaimed(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    // ---------- Outils ----------

    /** Refuse (et prévient) si le joueur n'a pas la permission à cet endroit. */
    // ---------- 1.7.0 : ce qui passait encore ----------

    /** Supports d'armure : prendre ou poser une pièce d'armure (événement à part du clic sur une entité). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(org.bukkit.event.player.PlayerArmorStandManipulateEvent event) {
        deny(event.getPlayer(), event.getRightClicked().getLocation(), ClanPermission.BUILD, event);
    }

    /** Clic « précis » sur une entité (supports d'armure, cadres) : son propre événement. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractAt(org.bukkit.event.player.PlayerInteractAtEntityEvent event) {
        Entity entity = event.getRightClicked();
        if (entity instanceof Villager || entity instanceof Monster || entity instanceof Player) {
            return;
        }
        deny(event.getPlayer(), entity.getLocation(), entity instanceof Animals ? ClanPermission.ANIMALS : ClanPermission.BUILD, event);
    }

    /**
     * Une entité qui touche un bloc : une flèche sur un bouton, une plaque ou une cible (REDSTONE du tireur) ; un monstre
     * ou un animal qui piétine une culture, une plaque (rien d'extérieur n'entre).
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(org.bukkit.event.entity.EntityInteractEvent event) {
        Location location = event.getBlock().getLocation();
        Player shooter = playerOf(event.getEntity());
        if (shooter != null) {
            ClanPermission permission = event.getBlock().getType() == Material.FARMLAND ? ClanPermission.CROPS : ClanPermission.REDSTONE;
            if (!access.allows(shooter, location, permission)) {
                event.setCancelled(true);
            }
            return;
        }
        if (access.isClaimed(location)) {
            event.setCancelled(true);
        }
    }

    /** Un projectile qui casse un bloc (pot décoré, fruit de chorus, verre fragile...) ou touche une cible. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onProjectileHit(org.bukkit.event.entity.ProjectileHitEvent event) {
        Block block = event.getHitBlock();
        Player shooter = playerOf(event.getEntity());
        if (block == null || shooter == null) {
            return;
        }
        Material type = block.getType();
        boolean fragile = type == Material.DECORATED_POT || type == Material.CHORUS_FLOWER || type == Material.POINTED_DRIPSTONE
                || type == Material.TARGET || Tag.CANDLES.isTagged(type) || type == Material.BELL;
        if (fragile && !access.allows(shooter, block.getLocation(), type == Material.TARGET || type == Material.BELL
                ? ClanPermission.REDSTONE : ClanPermission.BUILD)) {
            event.setCancelled(true);
        }
    }

    /** La canne à pêche qui tire un animal ou une entité hors d'un terrain. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFish(org.bukkit.event.player.PlayerFishEvent event) {
        Entity caught = event.getCaught();
        if (event.getState() == org.bukkit.event.player.PlayerFishEvent.State.CAUGHT_ENTITY && caught != null
                && !(caught instanceof Player) && !(caught instanceof Monster) && !(caught instanceof org.bukkit.entity.Item)) {
            deny(event.getPlayer(), caught.getLocation(), caught instanceof Animals ? ClanPermission.ANIMALS : ClanPermission.BUILD, event);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLeash(org.bukkit.event.entity.PlayerLeashEntityEvent event) {
        deny(event.getPlayer(), event.getEntity().getLocation(), ClanPermission.ANIMALS, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onUnleash(org.bukkit.event.player.PlayerUnleashEntityEvent event) {
        deny(event.getPlayer(), event.getEntity().getLocation(), ClanPermission.ANIMALS, event);
    }

    /** Monter dans un bateau, un wagonnet, sur un cheval d'un terrain. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleEnter(org.bukkit.event.vehicle.VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) {
            deny(player, event.getVehicle().getLocation(),
                    event.getVehicle() instanceof Animals ? ClanPermission.ANIMALS : ClanPermission.BUILD, event);
        }
    }

    /** Potions lancées : sans ANIMALS, elles ne touchent ni les animaux ni les villageois d'un terrain. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPotion(org.bukkit.event.entity.PotionSplashEvent event) {
        Player thrower = playerOf(event.getEntity());
        if (thrower == null) {
            return;
        }
        for (org.bukkit.entity.LivingEntity target : event.getAffectedEntities()) {
            if ((target instanceof Animals || target instanceof Villager) && !access.allows(thrower, target.getLocation(), ClanPermission.ANIMALS)) {
                event.setIntensity(target, 0);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLingering(org.bukkit.event.entity.AreaEffectCloudApplyEvent event) {
        if (!(event.getEntity().getSource() instanceof Player thrower)) {
            return;
        }
        event.getAffectedEntities().removeIf(target -> (target instanceof Animals || target instanceof Villager)
                && !access.allows(thrower, target.getLocation(), ClanPermission.ANIMALS));
    }

    /** L'éclair d'un trident (Canalisation) : pas sur un terrain qui n'autorise pas le feu. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLightning(org.bukkit.event.weather.LightningStrikeEvent event) {
        if (event.getCause() == org.bukkit.event.weather.LightningStrikeEvent.Cause.TRIDENT
                && access.protects(event.getLightning().getLocation(), ClanRule.FIRE)) {
            event.setCancelled(true);
        }
    }

    private void deny(Player player, Location location, ClanPermission permission, Cancellable event) {
        if (!access.allows(player, location, permission)) {
            event.setCancelled(true);
            warn(player, permission);
        }
    }

    private void warn(Player player, ClanPermission permission) {
        long now = System.currentTimeMillis();
        Long last = warned.put(player.getUniqueId(), now);
        if (last == null || now - last > 1000) {
            messages.actionBar(player, "claim.denied", "permission", messages.plain(player, "permission." + permission.id()));
        }
    }

    /** Permission demandée par un clic droit sur ce bloc ; null si le bloc ne fait rien. */
    private static ClanPermission interaction(Block block) {
        Material type = block.getType();
        if (Tag.DOORS.isTagged(type) || Tag.TRAPDOORS.isTagged(type) || Tag.FENCE_GATES.isTagged(type)) {
            return ClanPermission.DOORS;
        }
        if (Tag.BUTTONS.isTagged(type) || REDSTONE.contains(type)) {
            return ClanPermission.REDSTONE;
        }
        if (type == Material.SWEET_BERRY_BUSH || Tag.CAVE_VINES.isTagged(type)) {
            return ClanPermission.CROPS;
        }
        if (block.getState(false) instanceof InventoryHolder) {
            return ClanPermission.CONTAINERS;
        }
        if (EDITABLE.contains(type) || Tag.CANDLES.isTagged(type) || Tag.CAULDRONS.isTagged(type) || Tag.ALL_SIGNS.isTagged(type)
                || Tag.FLOWER_POTS.isTagged(type) || Tag.BEDS.isTagged(type)) {
            return ClanPermission.BUILD;
        }
        return null;
    }

    private static boolean changesBlock(Material item) {
        return TOOLS.contains(item) || Tag.ITEMS_HOES.isTagged(item) || Tag.ITEMS_SHOVELS.isTagged(item)
                || Tag.ITEMS_AXES.isTagged(item) || item.name().endsWith("_SPAWN_EGG");
    }

    /** Culture (semer, récolter) : CROPS suffit, sans BUILD. */
    private static boolean isCrop(Block block) {
        Material type = block.getType();
        return Tag.CROPS.isTagged(type) || type == Material.NETHER_WART || type == Material.COCOA
                || type == Material.SWEET_BERRY_BUSH || type == Material.MELON || type == Material.PUMPKIN;
    }

    /** Le joueur derrière une entité : lui-même, le tireur d'un projectile, l'allumeur d'une TNT. */
    private static Player playerOf(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player source) {
            return source;
        }
        return null;
    }
}
