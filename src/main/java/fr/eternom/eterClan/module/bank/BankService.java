package fr.eternom.eterClan.module.bank;

import fr.eternom.eterClan.module.clan.Clan;
import fr.eternom.eterClan.module.clan.ClanPermission;
import fr.eternom.eterClan.module.clan.ClanRepository;
import fr.eternom.eterClan.module.clan.ClanService;
import fr.eternom.eterClan.module.sync.ClanSync;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterEconomy.api.EconomyApi;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.BiFunction;

/**
 * La banque d'un clan. Chaque membre a son compte (lui seul y touche ; protégé de la perte d'argent à la mort) ; la
 * réserve est la caisse commune (y verser : tout membre ; en retirer : permission RESERVE). L'intérêt (taux réglé par
 * INTEREST, entre 0 et bank.max-interest) est pris chaque semaine sur les comptes et versé à la réserve (WeeklyCycle).
 * Argent rangé = argent retiré du porte-monnaie (Vault) : un retrait refusé ne crédite rien.
 */
public class BankService {

    private final JavaPlugin plugin;
    private final ClanRepository clans;
    private final ClanSync sync;
    private final ClanService clanService;
    private final Messages messages;
    private final double maxInterest;

    public BankService(JavaPlugin plugin, ClanRepository clans, ClanSync sync, ClanService clanService, Messages messages) {
        this.plugin = plugin;
        this.clans = clans;
        this.sync = sync;
        this.clanService = clanService;
        this.messages = messages;
        this.maxInterest = Math.max(0, plugin.getConfig().getDouble("bank.max-interest", 10));
    }

    /** Porte-monnaie -> son compte. */
    public void deposit(Player player, double amount, Runnable after) {
        clanService.clanOf(player).ifPresent(clan -> move(player, clan, amount, after, (economy, sum) -> {
            if (!economy.withdraw(player.getUniqueId(), sum, "EterClan · banque")) {
                return "bank.not-enough";
            }
            clans.deposit(player.getUniqueId(), sum);
            return "bank.deposited";
        }));
    }

    /** Son compte -> porte-monnaie. */
    public void withdraw(Player player, double amount, Runnable after) {
        clanService.clanOf(player).ifPresent(clan -> move(player, clan, amount, after, (economy, sum) -> {
            if (!clans.withdraw(player.getUniqueId(), sum)) {
                return "bank.account-not-enough";
            }
            economy.deposit(player.getUniqueId(), sum, "EterClan · banque");
            return "bank.withdrawn";
        }));
    }

    /** Porte-monnaie -> réserve du clan (tout membre peut donner). */
    public void giveToReserve(Player player, double amount, Runnable after) {
        clanService.clanOf(player).ifPresent(clan -> move(player, clan, amount, after, (economy, sum) -> {
            if (!economy.withdraw(player.getUniqueId(), sum, "EterClan · banque")) {
                return "bank.not-enough";
            }
            clans.addReserve(clan.id(), sum);
            return "bank.reserve-given";
        }));
    }

    /** Réserve -> porte-monnaie (permission RESERVE). */
    public void takeFromReserve(Player player, double amount, Runnable after) {
        clanService.clanWith(player, ClanPermission.RESERVE).ifPresent(clan -> move(player, clan, amount, after, (economy, sum) -> {
            if (!clans.takeReserve(clan.id(), sum)) {
                return "bank.reserve-not-enough";
            }
            economy.deposit(player.getUniqueId(), sum, "EterClan · banque");
            return "bank.reserve-taken";
        }));
    }

    public void setInterest(Player player, double rate, Runnable after) {
        clanService.clanWith(player, ClanPermission.INTEREST).ifPresent(clan -> {
            if (!(rate >= 0 && rate <= maxInterest)) { // refuse aussi un nombre illisible (NaN)
                messages.send(player, "bank.interest-invalid", "max", percent(maxInterest));
                return;
            }
            Tasks.async(plugin, player, () -> {
                clans.setInterest(clan.id(), rate);
                sync.clanChanged(clan.id());
                return true;
            }, done -> {
                messages.send(player, "bank.interest-set", "rate", percent(rate));
                after.run();
            }, () -> messages.send(player, "error.generic"));
        });
    }

    public double maxInterest() {
        return maxInterest;
    }

    public static String percent(double rate) {
        return String.valueOf(rate).replaceAll("\\.0$", "");
    }

    /** Un mouvement d'argent en tâche de fond (montant positif arrondi au centime, économie disponible), puis le message et after. */
    private void move(Player player, Clan clan, double amount, Runnable after, BiFunction<EconomyApi, Double, String> action) {
        EconomyApi economy = EconomyApi.get().orElse(null);
        if (economy == null) {
            messages.send(player, "economy.unavailable");
            return;
        }
        double rounded = Double.isFinite(amount) ? Math.floor(amount * 100) / 100 : 0;
        if (rounded <= 0) {
            messages.send(player, "bank.amount-invalid");
            return;
        }
        Tasks.async(plugin, player, () -> {
            String key = action.apply(economy, rounded);
            sync.clanChanged(clan.id());
            return key;
        }, key -> {
            messages.send(player, key, "amount", Money.format(rounded));
            player.playSound(player, key.startsWith("bank.not") || key.endsWith("not-enough")
                    ? Sound.ENTITY_VILLAGER_NO : Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }
}
