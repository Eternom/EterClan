package fr.eternom.eterClan.module.claim;

import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanPermission;
import fr.eternom.eterClan.module.clan.ClanRepository;
import fr.eternom.eterClan.module.clan.ClanService;
import fr.eternom.eterClan.module.clan.Journal;
import fr.eternom.eterClan.module.clan.Land;
import fr.eternom.eterClan.module.clan.Land.AccessMode;
import fr.eternom.eterClan.module.clan.Land.Pass;
import fr.eternom.eterClan.module.clan.Land.PassOffer;
import fr.eternom.eterClan.module.sync.ClanSync;
import fr.eternom.eterClan.module.zone.Zone;
import fr.eternom.eterClan.module.zone.ZoneRepository;
import fr.eternom.eterEconomy.api.EconomyApi;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

/**
 * Qui entre sur le terrain d'un clan (permission ACCESS) : le mode (ouvert, fermé, pass), l'offre de pass (prix, jours,
 * droits), la liste noire ; et les passes : achetés par n'importe quel joueur (porte-monnaie -> réserve), offerts à un
 * invité par un membre (INVITE, 1 h à 7 jours), retirés par ACCESS (le reste du prix est rendu au prorata).
 * Une parcelle à pass (Zone.Mode.PASS) vend son propre pass, au prix de la parcelle, avec les droits de l'offre du clan.
 */
public class AccessService {

    private static final long DAY_MILLIS = 86_400_000L;

    private final JavaPlugin plugin;
    private final ClanRepository clans;
    private final ZoneRepository zones;
    private final ClanSync sync;
    private final ClanService clanService;
    private final Journal journal;
    private final Messages messages;

    public AccessService(JavaPlugin plugin, ClanRepository clans, ZoneRepository zones, ClanSync sync, ClanService clanService,
                         Journal journal, Messages messages) {
        this.plugin = plugin;
        this.clans = clans;
        this.zones = zones;
        this.sync = sync;
        this.clanService = clanService;
        this.journal = journal;
        this.messages = messages;
    }

    // ---------- Réglages (ACCESS) ----------

    /** Ouvert -> fermé -> pass -> ouvert. */
    public void cycleMode(Player player, Clan clan, Runnable after) {
        AccessMode next = clan.land().mode().next();
        String actor = player.getName();
        write(player, clan, after, () -> {
            clans.setAccess(clan.id(), next, clan.land().offer());
            journal.add(clan.id(), Journal.Category.CLAN, actor, "access-mode", next.name().toLowerCase());
        }, "access.mode-set", "mode", messages.plain(player, "access.mode." + next.name().toLowerCase()));
    }

    /** Le prix et la durée du pass (prix 0 : pas de pass à vendre). */
    public void setOffer(Player player, Clan clan, double price, int days, Runnable after) {
        if (!(price >= 0) || days < 1 || days > 30) {
            messages.send(player, "access.bad-offer");
            return;
        }
        PassOffer offer = new PassOffer(Math.floor(price * 100) / 100, days, clan.land().offer().rights());
        write(player, clan, after, () -> clans.setAccess(clan.id(), clan.land().mode(), offer), "access.offer-set",
                "price", Money.format(offer.price()), "days", String.valueOf(days));
    }

    /** Ce qu'un pass permet : un droit de plus ou de moins (jamais construire). */
    public void toggleRight(Player player, Clan clan, ClanPermission right, Runnable after) {
        if (!PassOffer.ALLOWED.contains(right)) {
            return;
        }
        Set<ClanPermission> rights = EnumSet.noneOf(ClanPermission.class);
        rights.addAll(clan.land().offer().rights());
        if (!rights.remove(right)) {
            rights.add(right);
        }
        PassOffer offer = new PassOffer(clan.land().offer().price(), clan.land().offer().days(), rights);
        write(player, clan, after, () -> clans.setAccess(clan.id(), clan.land().mode(), offer), null);
    }

    /** Liste noire : ajoute ou retire un joueur (il n'agit ni n'entre plus nulle part sur le terrain). */
    public void toggleBanned(Player player, Clan clan, String name, Runnable after) {
        if (!clan.can(player.getUniqueId(), ClanPermission.ACCESS)) {
            messages.send(player, "clan.no-permission");
            return;
        }
        clanService.findPlayer(player, name, target -> {
            if (clan.isMember(target.uuid())) {
                messages.send(player, "access.ban-member");
                return;
            }
            Tasks.async(plugin, player, () -> {
                boolean banned = clans.toggleBanned(clan.id(), target.uuid(), target.name());
                journal.add(clan.id(), Journal.Category.CLAN, player.getName(), banned ? "ban" : "unban", target.name());
                sync.clanChanged(clan.id());
                return banned;
            }, banned -> {
                messages.send(player, banned ? "access.banned-added" : "access.banned-removed", "player", target.name());
                after.run();
            }, () -> messages.send(player, "error.generic"));
        });
    }

    // ---------- Passes ----------

    /** Acheter le pass du terrain (zone null) ou d'une parcelle à pass ; un pass en cours est prolongé. */
    public void buy(Player player, Clan clan, Zone zone, Runnable after) {
        Land land = clan.land();
        double price = zone == null ? land.offer().price() : zone.passPrice();
        int days = zone == null ? land.offer().days() : zone.passDays();
        boolean forSale = zone == null ? land.mode() == AccessMode.PASS && land.offer().forSale()
                : zone.mode() == Zone.Mode.PASS && zone.passPrice() > 0;
        if (!forSale) {
            messages.send(player, "access.not-for-sale");
            return;
        }
        if (clan.isMember(player.getUniqueId())) {
            messages.send(player, "access.member-no-pass");
            return;
        }
        if (land.banned().contains(player.getUniqueId())) {
            messages.send(player, "access.banned");
            return;
        }
        EconomyApi economy = EconomyApi.get().orElse(null);
        if (economy == null) {
            messages.send(player, "economy.unavailable");
            return;
        }
        long zoneId = zone == null ? 0 : zone.id();
        long now = System.currentTimeMillis();
        long base = land.passes().stream().filter(pass -> pass.player().equals(player.getUniqueId()) && pass.zoneId() == zoneId)
                .mapToLong(Pass::expiresAt).filter(at -> at > now).max().orElse(now);
        long expires = base + days * DAY_MILLIS;
        Tasks.async(plugin, player, () -> {
            if (!economy.withdraw(player.getUniqueId(), price, "EterClan · pass")) {
                return false;
            }
            clans.addReserve(clan.id(), price);
            clans.addPass(clan.id(), zoneId, player.getUniqueId(), player.getName(), expires, price, false);
            journal.add(clan.id(), Journal.Category.BANK, player.getName(), "bank-pass", Money.format(price),
                    zone == null ? "-" : zone.name());
            sync.clanChanged(clan.id());
            return true;
        }, bought -> {
            if (bought) {
                messages.send(player, "access.bought", "clan", clan.name(), "days", String.valueOf(days), "price", Money.format(price));
                clan.members().forEach(member -> sync.bus().notify(member.uuid(), "access.sold", false, "player", player.getName(),
                        "price", Money.format(price)));
            } else {
                messages.send(player, "access.no-money", "price", Money.format(price));
            }
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    /** Inviter un joueur sur le terrain, gratuitement, pour hours heures (INVITE ; 1 h à 7 jours). */
    public void invite(Player player, Clan clan, String name, int hours, Runnable after) {
        if (!clan.can(player.getUniqueId(), ClanPermission.INVITE)) {
            messages.send(player, "clan.no-permission");
            return;
        }
        int clamped = Math.max(1, Math.min(hours, 24 * 7));
        clanService.findPlayer(player, name, target -> {
            if (clan.isMember(target.uuid())) {
                messages.send(player, "access.member-no-pass");
                return;
            }
            long expires = System.currentTimeMillis() + Duration.ofHours(clamped).toMillis();
            Tasks.async(plugin, player, () -> {
                clans.addPass(clan.id(), 0, target.uuid(), target.name(), expires, 0, true);
                journal.add(clan.id(), Journal.Category.CLAN, player.getName(), "guest", target.name(), String.valueOf(clamped));
                sync.clanChanged(clan.id());
                return true;
            }, done -> {
                messages.send(player, "access.guest-added", "player", target.name(), "hours", String.valueOf(clamped));
                sync.bus().notify(target.uuid(), "access.guest-received", true, "clan", clan.name(), "hours", String.valueOf(clamped));
                after.run();
            }, () -> messages.send(player, "error.generic"));
        });
    }

    /** Retirer un pass (ACCESS) : ce qu'il restait est rendu au joueur, pris sur la réserve (si elle a assez). */
    public void revoke(Player player, Clan clan, Pass pass, Runnable after) {
        if (!clan.can(player.getUniqueId(), ClanPermission.ACCESS)) {
            messages.send(player, "clan.no-permission");
            return;
        }
        int days = pass.zoneId() == 0 ? clan.land().offer().days()
                : sync.zones().byId(pass.zoneId()).map(Zone::passDays).orElse(7);
        long left = Math.max(0, pass.expiresAt() - System.currentTimeMillis());
        double refund = pass.guest() ? 0 : Math.floor(pass.paid() * Math.min(1, left / (double) (days * DAY_MILLIS)) * 100) / 100;
        Tasks.async(plugin, player, () -> {
            if (clans.removePass(pass.id()).isEmpty()) {
                return false;
            }
            if (refund > 0 && clans.takeReserve(clan.id(), refund)) {
                EconomyApi.get().ifPresent(economy -> economy.deposit(pass.player(), refund, "EterClan · pass"));
                journal.add(clan.id(), Journal.Category.BANK, player.getName(), "bank-pass-refund", Money.format(refund), pass.name());
            }
            journal.add(clan.id(), Journal.Category.CLAN, player.getName(), "pass-revoked", pass.name());
            sync.clanChanged(clan.id());
            return true;
        }, done -> {
            if (done) {
                messages.send(player, "access.revoked", "player", pass.name(), "refund", Money.format(refund));
                sync.bus().notify(pass.player(), "access.revoked-player", true, "clan", clan.name(), "refund", Money.format(refund));
            }
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    // ---------- Parcelles ----------

    /** Le mode d'une parcelle : comme le terrain -> réservée -> fermée -> à pass -> comme le terrain (ZONES). */
    public void cycleZoneMode(Player player, Clan clan, Zone zone, Runnable after) {
        if (!clan.can(player.getUniqueId(), ClanPermission.ZONES)) {
            messages.send(player, "clan.no-permission");
            return;
        }
        Zone.Mode next = zone.mode().next();
        Tasks.async(plugin, player, () -> {
            zones.setAccess(zone.id(), next, zone.passPrice(), zone.passDays(), zone.publicRent());
            sync.zonesChanged();
            return true;
        }, done -> {
            messages.send(player, "zone.mode-set", "zone", zone.name(), "mode", messages.plain(player, "zone.mode." + next.name().toLowerCase()));
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    public void setZonePass(Player player, Clan clan, Zone zone, double price, int days, Runnable after) {
        if (!clan.can(player.getUniqueId(), ClanPermission.ZONES) || !(price >= 0) || days < 1 || days > 30) {
            messages.send(player, "access.bad-offer");
            return;
        }
        double rounded = Math.floor(price * 100) / 100;
        Tasks.async(plugin, player, () -> {
            zones.setAccess(zone.id(), zone.mode(), rounded, days, zone.publicRent());
            sync.zonesChanged();
            return true;
        }, done -> {
            messages.send(player, "access.offer-set", "price", Money.format(rounded), "days", String.valueOf(days));
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    /** Location ouverte aux joueurs hors du clan, ou réservée aux membres. */
    public void togglePublicRent(Player player, Clan clan, Zone zone, Runnable after) {
        if (!clan.can(player.getUniqueId(), ClanPermission.ZONES)) {
            messages.send(player, "clan.no-permission");
            return;
        }
        boolean next = !zone.publicRent();
        Tasks.async(plugin, player, () -> {
            zones.setAccess(zone.id(), zone.mode(), zone.passPrice(), zone.passDays(), next);
            sync.zonesChanged();
            return true;
        }, done -> {
            messages.send(player, next ? "zone.public-on" : "zone.public-off", "zone", zone.name());
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    private void write(Player player, Clan clan, Runnable after, Runnable action, String key, String... placeholders) {
        if (!clan.can(player.getUniqueId(), ClanPermission.ACCESS)) {
            messages.send(player, "clan.no-permission");
            return;
        }
        Tasks.async(plugin, player, () -> {
            action.run();
            sync.clanChanged(clan.id());
            return true;
        }, done -> {
            if (key != null) {
                messages.send(player, key, placeholders);
            }
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }
}
