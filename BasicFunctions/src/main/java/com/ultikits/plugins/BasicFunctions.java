package com.ultikits.plugins;

import com.ultikits.plugins.commands.GMChangeCommand;
import com.ultikits.plugins.commands.HealCommand;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.manager.CommandManager;
import lombok.Getter;

import java.io.IOException;
import java.util.List;

public class BasicFunctions extends UltiToolsPlugin {
    @Getter
    private static BasicFunctions instance;
    @Override
    public boolean registerSelf() throws IOException {
        instance = this;
        getCommandManager().register(new HealCommand(), "ultikits.tools.command.heal", i18n("指令治愈功能"), "heal", "h");
        getCommandManager().register(new GMChangeCommand(), "ultikits.tools.command.gm", this.i18n("游戏模式切换功能"), "gm");
        return false;
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

}
