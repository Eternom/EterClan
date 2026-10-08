package fr.eternom.eterClan.module.command;

import fr.eternom.eterClan.module.menu.ClanGui;
import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

/** /clan : la seule commande. Tout le reste (créer, inviter, banque, terrain, parcelles...) se fait dans le menu. */
public class ClanCommand implements TabExecutor {

    private final ClanGui gui;
    private final Messages messages;

    public ClanCommand(ClanGui gui, Messages messages) {
        this.gui = gui;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player) {
            gui.open(player);
        } else {
            messages.send(sender, "command.players-only");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return List.of();
    }
}
