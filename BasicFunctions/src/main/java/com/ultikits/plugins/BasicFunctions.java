package com.ultikits.plugins;

import com.ultikits.plugins.commands.HealCommand;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.manager.CommandManager;

import java.io.IOException;
import java.util.List;

public class BasicFunctions extends UltiToolsPlugin {
    private static BasicFunctions instance;
    @Override
    public boolean registerSelf() throws IOException {
        instance = this;
        new CommandManager().register(new HealCommand(), "ultikits.tools.command.heal", i18n("指令治愈功能"), "heal", "h");
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

    public static BasicFunctions getInstance(){
        return instance;
    }
}
