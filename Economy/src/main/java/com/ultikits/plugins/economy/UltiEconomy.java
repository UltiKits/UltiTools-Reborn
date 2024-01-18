package com.ultikits.plugins.economy;

import com.ultikits.plugins.economy.apis.BankService;
import com.ultikits.plugins.economy.config.EcoConfig;
import com.ultikits.plugins.economy.impls.UltiEconomyExpansion;
import com.ultikits.plugins.economy.task.InterestTask;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import lombok.Getter;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;

import java.util.Collections;
import java.util.List;

import static org.bukkit.Bukkit.getServer;

@UltiToolsModule
public class UltiEconomy extends UltiToolsPlugin {
    @Getter
    private static UltiEconomy economy;

    public UltiEconomy() {
        super();
        economy = this;
    }

    public static UltiEconomy getInstance() {
        return economy;
    }

    @Override
    public boolean registerSelf() {
        // Register the Vault economy service
        Bukkit.getServicesManager().register(Economy.class, UltiEconomy.getInstance().getContext().getBean(Economy.class), UltiTools.getInstance(), ServicePriority.Highest);
        Bukkit.getServicesManager().register(BankService.class, UltiEconomy.getInstance().getContext().getBean(BankService.class), UltiTools.getInstance(), ServicePriority.Highest);
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new UltiEconomyExpansion().register();
        }

        new InterestTask().runTaskTimerAsynchronously(UltiTools.getInstance(), 0L, getConfigManager().getConfigEntity(this, EcoConfig.class).getInterestTime() * 20L * 60);
        return true;
    }

    @Override
    public void unregisterSelf() {

    }

    @Override
    public List<AbstractConfigEntity> getAllConfigs() {
        return Collections.singletonList(
                new EcoConfig("config/config.yml")
        );
    }
}
