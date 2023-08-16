package com.ultikits.plugins.economy.task;

import com.ultikits.plugins.economy.EcoConfig;
import com.ultikits.plugins.economy.UltiEconomy;
import com.ultikits.plugins.economy.apis.BankService;
import com.ultikits.plugins.economy.entity.AccountEntity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;

public class InterestTask extends BukkitRunnable {
    @Override
    public void run() {
        // Add interest to all players' accounts according to the interest rate
        // The interest rate is stored in the config.yml file
        // The interest will be added to each of the player's account according to the amount in that account
        for (Player player : Bukkit.getOnlinePlayers()) {
            BankService bank = UltiEconomy.getBank();
            List<AccountEntity> accounts = bank.getAccounts(player.getUniqueId());
            if (accounts.isEmpty()){
                continue;
            }
            for (AccountEntity account : accounts) {
                double interest = account.getBalance() * (UltiEconomy.getInstance().getConfig(EcoConfig.class).getInterestRate());
                bank.addMoneyToAccount(account.getId().toString(), interest);
            }
            player.sendMessage(UltiEconomy.getInstance().i18n("你收到了来自银行的利息！"));
        }
    }
}
