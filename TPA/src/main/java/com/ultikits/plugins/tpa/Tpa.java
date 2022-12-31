package com.ultikits.plugins.tpa;

import com.ultikits.plugins.tpa.commands.TpCommand;
import com.ultikits.plugins.tpa.commands.TpHereCommand;
import com.ultikits.plugins.tpa.listeners.TpaListener;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ListenerManager;

import java.util.Arrays;
import java.util.List;

public class Tpa extends UltiToolsPlugin {
    public static Tpa tpa;

    @Override
    public boolean registerSelf() {
        tpa = this;
        new ListenerManager().registerListener(this, new TpaListener());
        new CommandManager().registerCommand(new TpCommand(), "ultikits.tools.tpa", this.i18n("tpa_function"), "tpa");
        new CommandManager().registerCommand(new TpHereCommand(), "ultikits.tools.tpa", this.i18n("tpa_function"),"tphere");
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
        return null;
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }

    public static Tpa getInstance() {
        return tpa;
    }
}
