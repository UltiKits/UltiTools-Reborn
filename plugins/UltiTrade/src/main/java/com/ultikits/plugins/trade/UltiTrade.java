package com.ultikits.plugins.trade;

import com.ultikits.plugins.trade.service.TradeService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * UltiTrade - Player-to-player trading system.
 * <p>
 * Features:
 * - Safe item trading between players
 * - Money trading support (Vault)
 * - Trade confirmation system
 * - Trade timeout
 * </p>
 *
 * @author wisdomme
 * @version 1.0.0
 */
@UltiToolsModule(scanBasePackages = {"com.ultikits.plugins.trade"})
public class UltiTrade extends UltiToolsPlugin {

    private static UltiTrade instance;

    public static UltiTrade getInstance() {
        return instance;
    }

    @Override
    public boolean registerSelf() {
        instance = this;
        
        TradeService tradeService = getContext().getBean(TradeService.class);
        if (tradeService != null) {
            tradeService.init();
        }
        
        getLogger().info(i18n("UltiTrade 已启用！"));
        return true;
    }

    @Override
    public void unregisterSelf() {
        TradeService tradeService = getContext().getBean(TradeService.class);
        if (tradeService != null) {
            tradeService.shutdown();
        }
        
        getLogger().info(i18n("UltiTrade 已禁用！"));
        instance = null;
    }

    @Override
    public void reloadSelf() {
        getLogger().info(i18n("UltiTrade 配置已重载！"));
    }
}
