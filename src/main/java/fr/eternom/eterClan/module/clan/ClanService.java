package fr.eternom.eterClan.module.clan;

import fr.eternom.eterClan.module.sync.ClanSync;
import fr.eternom.eterClan.module.zone.ZoneRepository;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.player.PlayerDirectory.NetworkPlayer;
import fr.eternom.eterEconomy.api.EconomyApi;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * La vie d'un clan : créer (payant), inviter (invitation gardée 5 min dans Redis), rejoindre, partir, exclure, céder,
 * dissoudre, et les permissions des membres. Thread principal ; la base et Vault en tâche de fond. Le compte d'un membre
 * qui part (ou est exclu) lui revient ; la réserve d'un clan dissous est perdue.
 */
public class ClanService {

    private static final Pattern NAME = Pattern.compile("[\\p{L}0-9 _'-]{3,24}");
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9]{2,5}");
    private static final Duration INVITE_TTL = Duration.ofMinutes(5);
    private static final Duration WEEK = Duration.ofDays(7);

    private final JavaPlugin plugin;
    private final ClanRepository clans;
    private final ZoneRepository zones;
    private final ClanSync sync;
    private final RedisCache redis;
    private final Messages messages;
    private final double creationPrice;
    private final double defaultInterest;
    private final Set<ClanPermission> defaultPermissions;

    public ClanService(JavaPlugin plugin, ClanRepository clans, ZoneRepository zones, ClanSync sync, RedisCache redis,
                       Messages messages) {
        this.plugin = plugin;
        this.clans = clans;
        this.zones = zones;
        this.sync = sync;
        this.redis = redis;
        this.messages = messages;
        FileConfiguration config = plugin.getConfig();
        this.creationPrice = Math.max(0, config.getDouble("clan.creation-price", 1000));
        this.defaultInterest = Math.max(0, config.getDouble("bank.default-interest", 2));
        Set<ClanPermission> permissions = EnumSet.noneOf(ClanPermission.class);
        config.getStringList("clan.default-permissions").forEach(id -> permissions.addAll(ClanPermission.parse(id)));
        this.defaultPermissions = permissions;
    }

    public double creationPrice() {
        return creationPrice;
    }

    /** Le clan du joueur (en mémoire) ; message s'il n'en a pas. */
    public Optional<Clan> clanOf(Player player) {
        Optional<Clan> clan = sync.cache().of(player.getUniqueId());
        if (clan.isEmpty()) {
            messages.send(player, "clan.none");
        }
        return clan;
    }

    /** Le clan du joueur, s'il a la permission ; message sinon. */
    public Optional<Clan> clanWith(Player player, ClanPermission permission) {
        return clanOf(player).filter(clan -> {
            if (clan.can(player.getUniqueId(), permission)) {
                return true;
            }
            messages.send(player, "clan.no-permission");
            return false;
        });
    }

    public void create(Player player, String name, String tag) {
        if (!NAME.matcher(name).matches() || !TAG.matcher(tag).matches()) {
            messages.send(player, "clan.create-usage");
            return;
        }
        if (sync.cache().of(player.getUniqueId()).isPresent()) {
            messages.send(player, "clan.already");
            return;
        }
        EconomyApi economy = EconomyApi.get().orElse(null);
        if (economy == null) {
            messages.send(player, "economy.unavailable");
            return;
        }
        UUID uuid = player.getUniqueId();
        String upperTag = tag.toUpperCase(Locale.ROOT);
        Tasks.async(plugin, player, () -> {
            if (clans.nameTaken(name, upperTag)) {
                return "clan.name-taken";
            }
            if (creationPrice > 0 && !economy.has(player.getUniqueId(), creationPrice)) {
                return "clan.create-not-enough";
            }
            // Le clan d'abord, l'argent ensuite : une erreur ne fait jamais payer un clan qui n'existe pas
            long now = System.currentTimeMillis();
            Optional<Long> id = clans.create(name, upperTag, uuid, player.getName(), defaultInterest, now, now + WEEK.toMillis());
            if (id.isEmpty()) {
                return "clan.already"; // déjà dans un clan (autre serveur)
            }
            if (creationPrice > 0 && !economy.withdraw(player.getUniqueId(), creationPrice, "EterClan · clan")) {
                clans.delete(id.get());
                return "clan.create-not-enough";
            }
            sync.clanChanged(id.get());
            return "clan.created";
        }, key -> {
            messages.send(player, key, "clan", name, "tag", upperTag, "price", Money.format(creationPrice));
            if (key.equals("clan.created")) {
                player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
            }
        }, () -> messages.send(player, "error.generic"));
    }

    public void invite(Player player, String targetName) {
        clanWith(player, ClanPermission.INVITE).ifPresent(clan -> findPlayer(player, targetName, target -> {
            if (sync.cache().of(target.uuid()).isPresent()) {
                messages.send(player, "clan.target-in-clan", "player", target.name());
                return;
            }
            Tasks.async(plugin, () -> redis.set(inviteKey(target.uuid()), String.valueOf(clan.id()), INVITE_TTL), "Invitation de clan");
            messages.send(player, "clan.invite-sent", "player", target.name());
            sync.bus().notify(target.uuid(), "clan.invited", true, "clan", clan.name(), "player", player.getName());
        }));
    }

    public void accept(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> {
            Optional<String> invite = redis.get(inviteKey(uuid));
            if (invite.isEmpty()) {
                return Optional.<Clan>empty();
            }
            redis.delete(inviteKey(uuid));
            long clanId = Long.parseLong(invite.get());
            if (!clans.addMember(clanId, uuid, player.getName(), defaultPermissions, System.currentTimeMillis())) {
                return Optional.<Clan>empty();
            }
            sync.clanChanged(clanId);
            return clans.byId(clanId);
        }, joined -> joined.ifPresentOrElse(clan -> {
            messages.send(player, "clan.joined", "clan", clan.name());
            clan.members().stream().filter(member -> !member.uuid().equals(uuid))
                    .forEach(member -> sync.bus().notify(member.uuid(), "clan.member-joined", false, "player", player.getName()));
        }, () -> messages.send(player, "clan.no-invite")), () -> messages.send(player, "error.generic"));
    }

    public void leave(Player player) {
        clanOf(player).ifPresent(clan -> {
            if (clan.isOwner(player.getUniqueId())) {
                messages.send(player, "clan.owner-cannot-leave");
                return;
            }
            remove(clan, player.getUniqueId(), () -> messages.send(player, "clan.left", "clan", clan.name()));
        });
    }

    public void kick(Player player, UUID member, Runnable after) {
        clanWith(player, ClanPermission.KICK).ifPresent(clan -> {
            Optional<Clan.Member> target = clan.member(member);
            if (target.isEmpty()) {
                messages.send(player, "clan.not-member", "player", "?");
            } else if (clan.isOwner(target.get().uuid())) {
                messages.send(player, "clan.cannot-kick-owner");
            } else {
                remove(clan, target.get().uuid(), () -> {
                    messages.send(player, "clan.kicked", "player", target.get().name());
                    sync.bus().notify(target.get().uuid(), "clan.you-were-kicked", true, "clan", clan.name());
                    after.run();
                });
            }
        });
    }

    public void transfer(Player player, UUID member, Runnable after) {
        clanOf(player).ifPresent(clan -> {
            Optional<Clan.Member> target = clan.member(member).filter(found -> !found.uuid().equals(player.getUniqueId()));
            if (!clan.isOwner(player.getUniqueId())) {
                messages.send(player, "clan.owner-only");
            } else if (target.isPresent()) {
                Tasks.async(plugin, player, () -> {
                    clans.setOwner(clan.id(), target.get().uuid());
                    sync.clanChanged(clan.id());
                    return true;
                }, done -> {
                    messages.send(player, "clan.transferred", "player", target.get().name());
                    sync.bus().notify(target.get().uuid(), "clan.you-are-owner", true, "clan", clan.name());
                    after.run();
                }, () -> messages.send(player, "error.generic"));
            }
        });
    }

    /** Dissout le clan : chaque compte revient à son membre, chunks et parcelles sont libérés, la réserve est perdue. */
    public void disband(Player player) {
        clanOf(player).ifPresent(clan -> {
            if (!clan.isOwner(player.getUniqueId())) {
                messages.send(player, "clan.owner-only");
                return;
            }
            EconomyApi economy = EconomyApi.get().orElse(null);
            Tasks.async(plugin, player, () -> {
                Map<UUID, Double> refunds = clans.delete(clan.id());
                if (economy != null) {
                    refunds.forEach((member, amount) -> {
                        if (amount > 0) {
                            economy.deposit(member, amount, "EterClan · clan");
                        }
                    });
                }
                sync.clanChanged(clan.id());
                sync.claimsChanged();
                sync.zonesChanged();
                return true;
            }, done -> {
                messages.send(player, "clan.disbanded", "clan", clan.name());
                clan.members().stream().filter(member -> !member.uuid().equals(player.getUniqueId()))
                        .forEach(member -> sync.bus().notify(member.uuid(), "clan.disbanded-notice", true, "clan", clan.name()));
            }, () -> messages.send(player, "error.generic"));
        });
    }

    /** Donne ou retire une permission à un membre (pas au chef, qui a tout). */
    public void togglePermission(Player player, Clan clan, UUID member, ClanPermission permission, Runnable after) {
        if (!clan.can(player.getUniqueId(), ClanPermission.PERMISSIONS)) {
            messages.send(player, "clan.no-permission");
            return;
        }
        if (clan.isOwner(member)) {
            messages.send(player, "clan.owner-has-all");
            return;
        }
        clan.member(member).ifPresent(target -> {
            Set<ClanPermission> next = EnumSet.noneOf(ClanPermission.class);
            next.addAll(target.permissions());
            if (!next.remove(permission)) {
                next.add(permission);
            }
            Tasks.async(plugin, player, () -> {
                clans.setPermissions(member, next);
                sync.clanChanged(clan.id());
                return true;
            }, done -> after.run(), () -> messages.send(player, "error.generic"));
        });
    }

    /** Afficher son grade ou le tag du clan (Tab, chat, pseudo) ; le staff garde toujours son grade. */
    public void toggleDisplay(Player player, Runnable after) {
        clanOf(player).ifPresent(clan -> {
            boolean showRank = !clan.member(player.getUniqueId()).map(Clan.Member::showRank).orElse(false);
            Tasks.async(plugin, player, () -> {
                clans.setShowRank(player.getUniqueId(), showRank);
                sync.clanChanged(clan.id());
                return showRank;
            }, rank -> {
                messages.send(player, player.hasPermission(ClanSync.STAFF) ? "clan.display-staff"
                        : rank ? "clan.display-rank" : "clan.display-clan", "tag", clan.tag());
                after.run();
            }, () -> messages.send(player, "error.generic"));
        });
    }

    /** Joueur déjà venu sur le réseau, par son pseudo (annuaire d'EterLib). */
    public void findPlayer(Player asker, String name, Consumer<NetworkPlayer> then) {
        Tasks.async(plugin, asker, () -> EterLib.get().getPlayers().find(name),
                found -> found.ifPresentOrElse(then, () -> messages.send(asker, "player.unknown", "player", name)),
                () -> messages.send(asker, "error.generic"));
    }

    /** Retire un membre : son compte lui est reversé (même hors ligne), ses locations s'arrêtent. */
    private void remove(Clan clan, UUID member, Runnable then) {
        EconomyApi economy = EconomyApi.get().orElse(null);
        Tasks.async(plugin, () -> {
            Optional<Double> account = clans.removeMember(clan.id(), member);
            if (account.isPresent() && account.get() > 0 && economy != null) {
                economy.deposit(member, account.get(), "EterClan · clan");
            }
            zones.releaseTenant(clan.id(), member);
            sync.zonesChanged();
            sync.clanChanged(clan.id());
            Bukkit.getScheduler().runTask(plugin, then);
        }, "Départ d'un membre du clan " + clan.id());
    }

    private static String inviteKey(UUID player) {
        return "clan:invite:" + player;
    }
}
