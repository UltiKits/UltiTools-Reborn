package com.ultikits.plugins.economy;

import com.ultikits.plugins.economy.apis.BankService;
import com.ultikits.plugins.economy.commands.BankCommand;
import com.ultikits.plugins.economy.commands.MoneyCommand;
import com.ultikits.plugins.economy.impls.BankServiceImpl;
import com.ultikits.plugins.economy.impls.UltiEconomyExpansion;
import com.ultikits.plugins.economy.impls.VaultImpl;
import com.ultikits.plugins.economy.listener.JoinListener;
import com.ultikits.plugins.economy.task.InterestTask;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import lombok.Getter;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.bukkit.Bukkit.getServer;

public final class UltiEconomy extends UltiToolsPlugin {
    private static UltiEconomy economy;
    @Getter
    private static Economy vault;
    @Getter
    private static BankService bank;

    public static UltiEconomy getInstance() {
        return economy;
    }

    @Override
    public boolean registerSelf() throws IOException {
        economy = this;
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

        new InterestTask().runTaskTimerAsynchronously(UltiTools.getInstance(), 0L, getConfig(EcoConfig.class).getInterestTime() * 20L * 60);
        return true;
    }

    @Override
    public void unregisterSelf() {

    }

    @Override
    public List<AbstractConfigEntity> getAllConfigs() {
        return Arrays.asList(
                new EcoConfig("res/config/config.yml")
        );
    }
}
