package com.ultikits.plugins;

import com.ultikits.plugins.commands.*;
import com.ultikits.plugins.config.JoinWelcomeConfig;
import com.ultikits.plugins.listeners.BackListener;
import com.ultikits.plugins.listeners.JoinWelcomeListener;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import lombok.Getter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class BasicFunctions extends UltiToolsPlugin {
    @Getter
    private static BasicFunctions instance;

    @Override
    public boolean registerSelf() throws IOException {
        instance = this;
        getCommandManager().register(new HealCommand(), "ultikits.tools.command.heal", i18n("指令治愈功能"), "heal", "h");
        getCommandManager().register(new GMChangeCommand(), "ultikits.tools.command.gm", i18n("游戏模式切换功能"), "gm");
        getCommandManager().register(new BackCommands(), "ultikits.tools.command.back", i18n("快捷返回功能"), "back");
        getCommandManager().register(new RandomTpCommands(), "ultikits.tools.command.wild", i18n("随机传送功能"), "wild");
        getCommandManager().register(new FlyCommands(), "ultikits.tools.command.fly", i18n("飞行功能"), "fly");
        getListenerManager().register(this, new JoinWelcomeListener());
        getListenerManager().register(this, new BackListener());
        return true;
    }

    @Override
    public void unregisterSelf() {

    }

    @Override
    public void reloadSelf() {
        super.reloadSelf();
    }

    @Override
    public List<String> supported() {
        return super.supported();
    }

    @Override
    public List<AbstractConfigEntity> getAllConfigs() {
        return Arrays.asList(
                new JoinWelcomeConfig("res/config/join.yml")
        );
    }
}
