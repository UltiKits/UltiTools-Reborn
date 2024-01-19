package com.ultikits.plugins.basic;

import com.ultikits.plugins.basic.commands.*;
import com.ultikits.plugins.basic.config.BasicConfig;
import com.ultikits.plugins.basic.config.PlayerNameTagConfig;
import com.ultikits.plugins.basic.config.WarpConfig;
import com.ultikits.plugins.basic.listeners.*;
import com.ultikits.plugins.basic.services.WarpService;
import com.ultikits.plugins.basic.tasks.NamePrefixSuffixTask;
import com.ultikits.plugins.basic.utils.BlueMapUtils;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;

@UltiToolsModule
public class BasicFunctions extends UltiToolsPlugin {
    @Getter
    private static BasicFunctions instance;

    public BasicFunctions() {
        super();
        instance = this;
    }

    @Override
    public boolean registerSelf() {
        BasicConfig configEntity = getConfigManager().getConfigEntity(this, BasicConfig.class);
        if (configEntity.isEnableHeal()) {
            getCommandManager().register(this, HideCommands.class);
        }
        if (configEntity.isEnableGmChange()) {
            getCommandManager().register(this, GMChangeCommand.class);
        }
        if (configEntity.isEnableBack()) {
            getCommandManager().register(this, BackCommands.class);
            getListenerManager().register(this, BackListener.class);
        }
        if (configEntity.isEnableRandomTeleport()) {
            getCommandManager().register(this, RandomTpCommands.class);
        }
        if (configEntity.isEnableFly()) {
            getCommandManager().register(this, FlyCommands.class);
        }
        if (configEntity.isEnableWhitelist()) {
            getCommandManager().register(this, WhitelistCommands.class);
            getListenerManager().register(this, WhitelistListener.class);
        }
        if (configEntity.isEnableJoinWelcome()) {
            getListenerManager().register(this, JoinWelcomeListener.class);
        }
        if (configEntity.isEnableTpa()) {
            getCommandManager().register(this, TpaCommands.class);
            getCommandManager().register(this, TpaHereCommands.class);
        }
        if (configEntity.isEnableSpeed()) {
            getCommandManager().register(this, SpeedCommands.class);
        }
        if (configEntity.isEnableBan()) {
            getCommandManager().register(this, BanCommands.class);
            getListenerManager().register(this, BanListener.class);
        }
        if (configEntity.isEnableWarp()) {
            getCommandManager().register(this, WarpCommands.class);
            WarpConfig warpConfig = getConfigManager().getConfigEntity(this, WarpConfig.class);
            if (warpConfig.isEnableBlueMap()) {
                WarpService warpService = getContext().getBean(WarpService.class);
                BlueMapUtils.initBlueMap(warpService.getAllWarps());
            }
        }
        if (configEntity.isEnableSpawn()) {
            getCommandManager().register(this, SpawnCommands.class);
            getCommandManager().register(this, SetSpawnCommands.class);
        }
        if (configEntity.isEnableLoreEditor()) {
            getCommandManager().register(this, LoreCommands.class);
        }
        if (configEntity.isEnableHide()) {
            getCommandManager().register(this, HideCommands.class);
            getListenerManager().register(this, HideListener.class);
        }
        if (configEntity.isEnableTitle()) {
            getListenerManager().register(this, PlayerNameTagListener.class);
            int updateInterval = getConfig(PlayerNameTagConfig.class).getUpdateInterval();
            new NamePrefixSuffixTask().runTaskTimer(UltiTools.getInstance(), 0, updateInterval);
        }
        if (configEntity.isEnableDeathPunishment()) {
            getListenerManager().register(this, DeathListener.class);
        }
        return true;
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }
}
