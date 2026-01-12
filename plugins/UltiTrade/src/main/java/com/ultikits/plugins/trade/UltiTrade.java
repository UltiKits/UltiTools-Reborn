package com.ultikits.plugins.trade;

import com.ultikits.plugins.trade.placeholder.TradePlaceholderExpansion;
import com.ultikits.plugins.trade.service.TradeLogService;
import com.ultikits.plugins.trade.service.TradeService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

import org.bukkit.Bukkit;

/**
 * UltiTrade - Player-to-player trading system.
 * <p>
 * Features:
 * - Safe item trading between players
 * - Money trading support (Vault)
 * - Experience trading support
 * - Trade confirmation system
 * - Trade timeout with BossBar countdown
 * - Trade logging and statistics
 * - Player blacklist
 * - Shift+right-click trading
 * - Large trade confirmation
 * - PlaceholderAPI integration
 * </p>
 *
 * @author wisdomme
 * @version 2.0.0
 */
@UltiToolsModule(scanBasePackages = {"com.ultikits.plugins.trade"})
public class UltiTrade extends UltiToolsPlugin {

    private static UltiTrade instance;
    private TradePlaceholderExpansion placeholderExpansion;

    public static UltiTrade getInstance() {
        return instance;
    }

    @Override
    public boolean registerSelf() {
        instance = this;
        
        // Initialize TradeLogService
        TradeLogService logService = getContext().getBean(TradeLogService.class);
        if (logService != null) {
            logService.init();
        }
        
        // Initialize TradeService
        TradeService tradeService = getContext().getBean(TradeService.class);
        if (tradeService != null) {
            tradeService.init();
        }
        
        // Register PlaceholderAPI expansion if available
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholderExpansion = new TradePlaceholderExpansion(this, tradeService, logService);
            if (placeholderExpansion.register()) {
                getLogger().info("PlaceholderAPI 扩展已注册！");
            }
        } else {
            getLogger().info("PlaceholderAPI 未找到，跳过 Placeholder 注册。");
        }
        
        getLogger().info(i18n("UltiTrade 已启用！"));
        return true;
    }

    @Override
    public void unregisterSelf() {
        // Shutdown TradeService
        TradeService tradeService = getContext().getBean(TradeService.class);
        if (tradeService != null) {
            tradeService.shutdown();
        }
        
        // Shutdown TradeLogService
        TradeLogService logService = getContext().getBean(TradeLogService.class);
        if (logService != null) {
            logService.shutdown();
        }
        
        // Unregister PlaceholderAPI expansion
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
        
        getLogger().info(i18n("UltiTrade 已禁用！"));
        instance = null;
    }

    @Override
    public void reloadSelf() {
        getLogger().info(i18n("UltiTrade 配置已重载！"));
    }
}
