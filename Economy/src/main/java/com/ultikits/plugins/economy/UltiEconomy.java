package com.ultikits.plugins.economy;

import com.ultikits.plugins.economy.commands.BankCommand;
import com.ultikits.plugins.economy.commands.MoneyCommand;
import com.ultikits.plugins.economy.impls.BankServiceImpl;
import com.ultikits.plugins.economy.impls.UltiEconomyExpansion;
import com.ultikits.plugins.economy.impls.VaultImpl;
import com.ultikits.plugins.economy.listener.JoinListener;
import com.ultikits.plugins.economy.task.InterestTask;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.plugins.economy.apis.BankService;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;

import java.io.IOException;

import static org.bukkit.Bukkit.getServer;

public final class UltiEconomy extends UltiToolsPlugin {
    private static UltiEconomy economy;
    private static Economy vault;
    private static BankService bank;

    public static UltiEconomy getInstance() {
        return economy;
    }

    public static Economy getVault() {
        return vault;
    }

    public static BankService getBank() {
        return bank;
    }

    @Override
    public boolean registerSelf() throws IOException {
        economy = this;
        EcoConfig ecoConfig = new EcoConfig("res/config/config.yml");
        getConfigManager().register(this, ecoConfig);

        vault = new VaultImpl();
        bank = new BankServiceImpl();
        Bukkit.getServicesManager().register(Economy.class, vault, UltiTools.getInstance(), ServicePriority.Highest);
        Bukkit.getServicesManager().register(BankService.class, bank, UltiTools.getInstance(), ServicePriority.Highest);
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new UltiEconomyExpansion().register();
        }

        getCommandManager().register(new MoneyCommand(), "", "Money", "money");
        getCommandManager().register(new BankCommand(), "", "Bank", "bank");
        getListenerManager().register(this, new JoinListener());

        new InterestTask().runTaskTimerAsynchronously(UltiTools.getInstance(), 0L, ecoConfig.getInterestTime() * 20L * 60);
        return true;
    }

    @Override
    public void unregisterSelf() {

    }
}
