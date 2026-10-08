package fr.eternom.eterClan;

import fr.eternom.eterClan.listeners.Commands;
import fr.eternom.eterClan.listeners.Events;
import fr.eternom.eterClan.module.bank.BankService;
import fr.eternom.eterClan.module.bank.WeeklyCycle;
import fr.eternom.eterClan.module.claim.Access;
import fr.eternom.eterClan.module.claim.ClaimRepository;
import fr.eternom.eterClan.module.claim.ClaimService;
import fr.eternom.eterClan.module.claim.Pricing;
import fr.eternom.eterClan.module.clan.ClanRepository;
import fr.eternom.eterClan.module.clan.ClanService;
import fr.eternom.eterClan.module.menu.ClanGui;
import fr.eternom.eterClan.module.sync.ClanSync;
import fr.eternom.eterClan.module.zone.ZoneRepository;
import fr.eternom.eterClan.module.zone.ZoneService;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.sql.Database;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Les clans du réseau : des guildes (membres et permissions, banque avec un compte par membre et une réserve), leur
 * terrain en chunks (payé au serveur, hors d'atteinte), et les parcelles qu'ils louent à leurs membres. Le clan existe
 * sur tout le réseau ; chaque chunk et chaque parcelle restent sur le serveur où ils ont été posés. À installer partout :
 * le tag du clan remplace le grade partout (badge) ; le terrain ne se prend que là où land.enabled est vrai (la survie).
 */
public final class Main extends JavaPlugin {

    /** Version minimale d'EterLib : Redis obligatoire (invitations) et complétion des pseudos réseau depuis 1.8.0. */
    private static final String REQUIRED_ETERLIB = "1.8.0";
    /** Préfixe des tables : eterclan_clans, eterclan_members, eterclan_claims, eterclan_zones. */
    private static final String TABLE_PREFIX = "eterclan_";

    private Messages messages;
    private ClanSync sync;
    private ClanService clans;
    private BankService bank;
    private ClaimService land;
    private ZoneService zones;
    private Access access;
    private ClanGui gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // En premier : vérifie la version d'EterLib (un EterLib < 1.3.0 n'a pas requireVersion, d'où le catch)
        try {
            if (!EterLib.requireVersion(this, REQUIRED_ETERLIB)) {
                return;
            }
        } catch (LinkageError tooOld) {
            getLogger().severe("EterLib " + REQUIRED_ETERLIB + " ou plus récent est nécessaire.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        EterLib lib = EterLib.get();
        messages = lib.messages(this, "en_us", "fr_fr");
        Database database = lib.database(TABLE_PREFIX);
        // Les chunks et les parcelles d'abord : les clans comptent leurs chunks à la lecture
        ClaimRepository claimRepository = new ClaimRepository(database);
        ZoneRepository zoneRepository = new ZoneRepository(database);
        ClanRepository clanRepository = new ClanRepository(database);
        Pricing pricing = Pricing.load(getConfig().getConfigurationSection("land"));

        sync = new ClanSync(this, messages, lib.network(this, "eterclan", messages), lib.getServerName(), clanRepository,
                claimRepository, zoneRepository);
        sync.start();
        clans = new ClanService(this, clanRepository, zoneRepository, sync, lib.getRedis(), messages);
        bank = new BankService(this, clanRepository, sync, clans, messages);
        land = new ClaimService(this, claimRepository, zoneRepository, clanRepository, sync, clans, messages, pricing);
        zones = new ZoneService(this, zoneRepository, clanRepository, sync, clans, messages);
        access = new Access(sync);
        gui = new ClanGui(this, sync, clans, bank, land, messages, lib.backButton(getConfig().getString("menus.clan.back-command", "")));
        new WeeklyCycle(this, clanRepository, claimRepository, zoneRepository, sync, pricing).start();

        new Commands(this);
        new Events(this);
    }

    public Messages getMessages() {
        return messages;
    }

    public ClanSync getSync() {
        return sync;
    }

    public ClanService getClans() {
        return clans;
    }

    public BankService getBank() {
        return bank;
    }

    public ClaimService getLand() {
        return land;
    }

    public ZoneService getZones() {
        return zones;
    }

    public Access getAccess() {
        return access;
    }

    public ClanGui getGui() {
        return gui;
    }
}
