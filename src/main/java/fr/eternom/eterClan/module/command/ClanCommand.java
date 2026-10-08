package fr.eternom.eterClan.module.command;

import fr.eternom.eterClan.module.bank.BankService;
import fr.eternom.eterClan.module.claim.ClaimService;
import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanService;
import fr.eternom.eterClan.module.menu.ClanGui;
import fr.eternom.eterClan.module.sync.ClanSync;
import fr.eternom.eterClan.module.zone.ZoneService;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;

/**
 * /clan : le menu. Sous-commandes : create, invite, accept, leave, kick, transfer, disband confirm, claim, unclaim, here,
 * deposit, withdraw, give, take, interest, display (grade ou tag du clan), et zone (pos1, pos2, create, delete, rent,
 * take, end, trust, untrust, list).
 */
public class ClanCommand implements TabExecutor {

    private static final List<String> ACTIONS = List.of("create", "invite", "accept", "leave", "kick", "transfer", "disband",
            "claim", "unclaim", "here", "deposit", "withdraw", "give", "take", "interest", "display", "zone");
    private static final List<String> ZONE_ACTIONS = List.of("pos1", "pos2", "create", "delete", "rent", "take", "end",
            "trust", "untrust", "list");

    private final ClanGui gui;
    private final ClanService clans;
    private final BankService bank;
    private final ClaimService land;
    private final ZoneService zones;
    private final ClanSync sync;
    private final Messages messages;

    public ClanCommand(ClanGui gui, ClanService clans, BankService bank, ClaimService land, ZoneService zones, ClanSync sync,
                       Messages messages) {
        this.gui = gui;
        this.clans = clans;
        this.bank = bank;
        this.land = land;
        this.zones = zones;
        this.sync = sync;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.players-only");
            return true;
        }
        if (args.length == 0) {
            if (sync.cache().of(player.getUniqueId()).isPresent()) {
                gui.open(player);
            } else {
                messages.send(player, "clan.help-no-clan");
            }
            return true;
        }
        Runnable nothing = () -> { };
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length < 3) {
                    messages.send(player, "clan.create-usage");
                } else {
                    clans.create(player, String.join(" ", List.of(args).subList(1, args.length - 1)), args[args.length - 1]);
                }
            }
            case "invite" -> withName(player, args, name -> clans.invite(player, name));
            case "accept" -> clans.accept(player);
            case "leave" -> clans.leave(player);
            case "kick" -> withName(player, args, name -> clans.kick(player, name));
            case "transfer" -> withName(player, args, name -> clans.transfer(player, name));
            case "disband" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("confirm")) {
                    clans.disband(player);
                } else {
                    messages.send(player, "clan.disband-confirm");
                }
            }
            case "claim" -> land.claim(player);
            case "unclaim" -> land.unclaim(player);
            case "here" -> land.here(player);
            case "deposit" -> withAmount(player, args, 1, amount -> bank.deposit(player, amount, nothing));
            case "withdraw" -> withAmount(player, args, 1, amount -> bank.withdraw(player, amount, nothing));
            case "give" -> withAmount(player, args, 1, amount -> bank.giveToReserve(player, amount, nothing));
            case "take" -> withAmount(player, args, 1, amount -> bank.takeFromReserve(player, amount, nothing));
            case "interest" -> withAmount(player, args, 1, rate -> bank.setInterest(player, rate, nothing));
            case "display" -> clans.toggleDisplay(player, nothing);
            case "zone" -> zone(player, args);
            default -> messages.send(player, "clan.usage");
        }
        return true;
    }

    private void zone(Player player, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        String name = args.length > 2 ? args[2] : null;
        switch (action) {
            case "pos1" -> zones.select(player, 0);
            case "pos2" -> zones.select(player, 1);
            case "list" -> zones.list(player);
            case "create", "delete", "take", "end" -> {
                if (name == null) {
                    messages.send(player, "zone.usage");
                } else if (action.equals("create")) {
                    zones.create(player, name);
                } else if (action.equals("delete")) {
                    zones.delete(player, name);
                } else if (action.equals("take")) {
                    zones.take(player, name);
                } else {
                    zones.end(player, name);
                }
            }
            case "rent" -> {
                if (name == null) {
                    messages.send(player, "zone.usage");
                } else {
                    withAmount(player, args, 3, rent -> zones.setRent(player, name, rent));
                }
            }
            case "trust", "untrust" -> {
                if (name == null || args.length < 4) {
                    messages.send(player, "zone.usage");
                } else {
                    zones.trust(player, name, args[3], action.equals("trust"));
                }
            }
            default -> messages.send(player, "zone.usage");
        }
    }

    private void withName(Player player, String[] args, Consumer<String> then) {
        if (args.length < 2) {
            messages.send(player, "clan.usage");
        } else {
            then.accept(args[1]);
        }
    }

    private void withAmount(Player player, String[] args, int index, DoubleConsumer then) {
        if (args.length <= index) {
            messages.send(player, "bank.amount-invalid");
            return;
        }
        try {
            then.accept(Double.parseDouble(args[index].replace(',', '.')));
        } catch (NumberFormatException e) {
            messages.send(player, "bank.amount-invalid");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return filter(ACTIONS.stream(), args[0]);
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && action.equals("invite")) {
            return EterLib.get().getOnlineNames().complete(args[1]);
        }
        if (args.length == 2 && (action.equals("kick") || action.equals("transfer")) && sender instanceof Player player) {
            return filter(sync.cache().of(player.getUniqueId()).map(Clan::members).orElse(List.of()).stream()
                    .map(Clan.Member::name), args[1]);
        }
        if (args.length == 2 && action.equals("disband")) {
            return filter(Stream.of("confirm"), args[1]);
        }
        if (action.equals("zone") && args.length == 2) {
            return filter(ZONE_ACTIONS.stream(), args[1]);
        }
        if (action.equals("zone") && args.length == 3 && sender instanceof Player player) {
            return filter(sync.cache().of(player.getUniqueId()).stream()
                    .flatMap(clan -> sync.zones().ofClan(clan.id()).stream().map(zone -> zone.name())), args[2]);
        }
        if (action.equals("zone") && args.length == 4 && (args[1].equalsIgnoreCase("trust") || args[1].equalsIgnoreCase("untrust"))) {
            return EterLib.get().getOnlineNames().complete(args[3]);
        }
        return List.of();
    }

    private static List<String> filter(Stream<String> values, String start) {
        String prefix = start.toLowerCase(Locale.ROOT);
        return values.filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
