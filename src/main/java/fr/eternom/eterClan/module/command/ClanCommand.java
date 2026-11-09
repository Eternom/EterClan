package fr.eternom.eterClan.module.command;

import fr.eternom.eterClan.module.claim.AccessService;
import fr.eternom.eterClan.module.claim.ClaimIndex.ChunkKey;
import fr.eternom.eterClan.module.claim.ClaimRepository;
import fr.eternom.eterClan.module.claim.ClaimService;
import fr.eternom.eterClan.module.claim.LandPlan;
import fr.eternom.eterClan.module.claim.LandPlan.Plot;
import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanRepository;
import fr.eternom.eterClan.module.clan.Journal;
import fr.eternom.eterClan.module.menu.ClanGui;
import fr.eternom.eterClan.module.sync.ClanSync;
import fr.eternom.eterClan.module.zone.Zone;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;

/**
 * /clan : le menu (tout se fait dedans). Raccourcis : /clan home, /clan sethome (le home du clan), /clan pass <clan>
 * <parcelle> (le bouton d'achat d'un pass, envoyé dans le chat). Staff (eterclan.admin) : /clan admin info <clan>,
 * /clan admin unclaim (le chunk où l'on est, quel que soit son clan), /clan admin transfer <clan> <membre>.
 */
public class ClanCommand implements TabExecutor {

    public static final String ADMIN = "eterclan.admin";

    private final JavaPlugin plugin;
    private final ClanGui gui;
    private final ClaimService land;
    private final AccessService access;
    private final ClanRepository clans;
    private final ClaimRepository claims;
    private final ClanSync sync;
    private final Journal journal;
    private final Messages messages;

    public ClanCommand(JavaPlugin plugin, ClanGui gui, ClaimService land, AccessService access, ClanRepository clans,
                       ClaimRepository claims, ClanSync sync, Journal journal, Messages messages) {
        this.plugin = plugin;
        this.gui = gui;
        this.land = land;
        this.access = access;
        this.clans = clans;
        this.claims = claims;
        this.sync = sync;
        this.journal = journal;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.players-only");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "home" -> land.home(player);
            case "sethome" -> land.setHome(player);
            case "pass" -> buyPass(player, args);
            case "admin" -> admin(player, args);
            default -> gui.open(player);
        }
        return true;
    }

    /** /clan pass <clan> <parcelle> : acheter le pass (0 = le terrain). */
    private void buyPass(Player player, String[] args) {
        if (args.length < 3) {
            return;
        }
        try {
            long clanId = Long.parseLong(args[1]);
            long zoneId = Long.parseLong(args[2]);
            sync.cache().get(clanId).ifPresent(clan -> {
                Zone zone = zoneId == 0 ? null : sync.zones().byId(zoneId).orElse(null);
                access.buy(player, clan, zone, () -> { });
            });
        } catch (NumberFormatException ignored) {
            // bouton abîmé : rien
        }
    }

    private void admin(Player player, String[] args) {
        if (!player.hasPermission(ADMIN)) {
            messages.send(player, "clan.no-permission");
            return;
        }
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "info" -> {
                if (args.length < 3) {
                    messages.send(player, "admin.usage");
                    return;
                }
                Tasks.async(plugin, player, () -> clans.byName(args[2]), found -> found.ifPresentOrElse(clan -> info(player, clan),
                        () -> messages.send(player, "admin.unknown", "clan", args[2])), () -> messages.send(player, "error.generic"));
            }
            case "unclaim" -> {
                ChunkKey chunk = ChunkKey.of(player.getLocation());
                var owner = sync.claims().owner(chunk);
                if (owner.isEmpty()) {
                    messages.send(player, "claim.here-wild");
                    return;
                }
                long clanId = owner.getAsLong();
                Plot plot = land.plot(chunk);
                Tasks.async(plugin, player, () -> {
                    boolean removed = claims.unclaim(plot.server(), plot.world(), plot.x(), plot.z(), clanId);
                    if (removed) {
                        clans.byId(clanId).filter(clan -> clan.chunks() == 0).ifPresent(clan -> clans.clearHeart(clanId));
                        journal.add(clanId, Journal.Category.CLAN, player.getName(), "admin-unclaim", plot.world(),
                                String.valueOf(plot.x()), String.valueOf(plot.z()));
                        sync.claimsChanged();
                        sync.clanChanged(clanId);
                    }
                    return removed;
                }, removed -> messages.send(player, removed ? "admin.unclaimed" : "error.generic"), () -> messages.send(player, "error.generic"));
            }
            case "transfer" -> {
                if (args.length < 4) {
                    messages.send(player, "admin.usage");
                    return;
                }
                Tasks.async(plugin, player, () -> clans.byName(args[2]).flatMap(clan -> clan.members().stream()
                        .filter(member -> member.name().equalsIgnoreCase(args[3])).findFirst().map(member -> {
                            clans.setOwner(clan.id(), member.uuid());
                            journal.add(clan.id(), Journal.Category.CLAN, player.getName(), "admin-transfer", member.name());
                            sync.clanChanged(clan.id());
                            return member.name();
                        })), name -> messages.send(player, name.isPresent() ? "admin.transferred" : "admin.unknown",
                        "clan", args[2], "player", name.orElse(args.length > 3 ? args[3] : "")), () -> messages.send(player, "error.generic"));
            }
            default -> messages.send(player, "admin.usage");
        }
    }

    /** Le clan en quelques lignes : chef, membres, réserve, dette, terrain (cœur, extensions, avant-postes), niveau. */
    private void info(Player player, Clan clan) {
        int[] paid = LandPlan.paidCounts(clan.land().heart(), clan.land().plots());
        Plot heart = clan.land().heart();
        messages.send(player, "admin.info", "clan", clan.name(), "tag", clan.tag(),
                "members", String.valueOf(clan.members().size()), "reserve", Money.format(clan.reserve()),
                "debt", Money.format(clan.land().debt()), "chunks", String.valueOf(clan.chunks()),
                "extensions", String.valueOf(paid[0]), "outposts", String.valueOf(paid[1]),
                "outpost-groups", String.valueOf(LandPlan.outposts(heart, clan.land().plots()).size()),
                "heart", heart == null ? "-" : heart.server() + " " + heart.world() + " " + (heart.x() << 4) + " " + (heart.z() << 4),
                "level", String.valueOf(land.level(clan)), "upkeep", Money.format(land.upkeep(clan).total()));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> subs = sender.hasPermission(ADMIN) ? List.of("home", "sethome", "admin") : List.of("home", "sethome");
            return subs.stream().filter(sub -> sub.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin") && sender.hasPermission(ADMIN)) {
            return List.of("info", "unclaim", "transfer").stream().filter(sub -> sub.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
