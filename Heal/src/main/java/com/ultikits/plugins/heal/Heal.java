package com.ultikits.plugins.heal;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.manager.CommandManager;

import java.util.Arrays;
import java.util.List;

public class Heal extends UltiToolsPlugin {

    private static Heal heal;

    @Override
    public boolean registerSelf() {
        heal = this;
        new CommandManager().registerCommand(new CmdExecutor(), "ultikits.tools.command.heal", i18n("heal_function"), "heal", "h");
        return true;
    }

    @Override
    public void unregisterSelf() {
        //
    }

    @Override
    public void reloadSelf() {
        super.reloadSelf();
    }

    @Override
    public String pluginName() {
        return "UltiTools-Heal";
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }

    public static Heal getInstance() {
        return heal;
    }
}
