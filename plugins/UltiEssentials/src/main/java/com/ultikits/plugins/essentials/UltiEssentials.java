package com.ultikits.plugins.essentials;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * UltiEssentials - Essential commands and features for Minecraft servers.
 * <p>
 * This plugin provides common essential commands like teleportation, player status,
 * and server management features built on the UltiTools-API framework.
 * </p>
 * <p>
 * All services are automatically initialized by the IoC container via {@code @PostConstruct}.
 * </p>
 *
 * @author wisdommen
 * @author UltiKits Team
 * @version 1.0.0
 */
@UltiToolsModule(scanBasePackages = {"com.ultikits.plugins.essentials"})
public class UltiEssentials extends UltiToolsPlugin {

    private static UltiEssentials instance;

    /**
     * Gets the plugin instance.
     *
     * @return the UltiEssentials instance
     */
    public static UltiEssentials getInstance() {
        return instance;
    }

    @Override
    public boolean registerSelf() {
        instance = this;
        // All services are automatically initialized by IoC container via @PostConstruct
        getLogger().info(i18n("UltiEssentials 已启用！"));
        return true;
    }

    @Override
    public void unregisterSelf() {
        getLogger().info(i18n("UltiEssentials 已禁用！"));
        instance = null;
    }

    @Override
    public void reloadSelf() {
        getLogger().info(i18n("UltiEssentials 配置已重载！"));
    }
}

