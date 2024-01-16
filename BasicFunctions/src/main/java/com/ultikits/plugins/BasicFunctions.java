package com.ultikits.plugins;

import com.ultikits.plugins.commands.*;
import com.ultikits.plugins.config.BasicConfig;
import com.ultikits.plugins.config.JoinWelcomeConfig;
import com.ultikits.plugins.config.PlayerNameTagConfig;
import com.ultikits.plugins.config.WarpConfig;
import com.ultikits.plugins.listeners.*;
import com.ultikits.plugins.services.WarpService;
import com.ultikits.plugins.tasks.NamePrefixSuffixTask;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import de.bluecolored.bluemap.api.BlueMapAPI;
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
                BlueMapAPI.onEnable(api -> {
                    WarpService warpService = getContext().getBean(WarpService.class);
                    warpService.initBlueMap();
                });
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

    @Override
    public List<AbstractConfigEntity> getAllConfigs() {
        return Arrays.asList(
                new BasicConfig("config/config.yml"),
                new JoinWelcomeConfig("config/join.yml"),
                new PlayerNameTagConfig("config/title.yml")
        );
    }
}
