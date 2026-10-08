package fr.eternom.eterClan.listeners;

import fr.eternom.eterClan.Main;
import fr.eternom.eterClan.module.claim.LandListener;
import fr.eternom.eterClan.module.claim.ProtectionListener;
import org.bukkit.event.Listener;

public class Events {

    public Events(Main main) {
        register(main, new ProtectionListener(main.getAccess(), main.getMessages()));
        register(main, main.getSelection());
        register(main, new LandListener(main.getSync(), main.getMessages(), main.getLand().isEnabled()));
    }

    private static void register(Main main, Listener listener) {
        main.getServer().getPluginManager().registerEvents(listener, main);
    }
}
