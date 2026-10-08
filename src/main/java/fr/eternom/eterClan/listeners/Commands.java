package fr.eternom.eterClan.listeners;

import fr.eternom.eterClan.Main;
import fr.eternom.eterClan.module.command.ClanCommand;
import org.bukkit.command.PluginCommand;

import java.util.Objects;

public class Commands {

    public Commands(Main main) {
        ClanCommand clan = new ClanCommand(main.getGui(), main.getMessages());
        PluginCommand command = Objects.requireNonNull(main.getCommand("clan"), "Commande absente du plugin.yml : clan");
        command.setExecutor(clan);
        command.setTabCompleter(clan);
    }
}
