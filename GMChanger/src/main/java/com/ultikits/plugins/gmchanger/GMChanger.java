package com.ultikits.plugins.gmchanger;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

import java.util.Arrays;
import java.util.List;

/*
 GMChanger-游戏模式切换模块
 @author qianmo
*/

public class GMChanger extends UltiToolsPlugin {
    private static GMChanger gmChanger;

    @Override
    public boolean registerSelf() {
        gmChanger = this;
        getCommandManager().register(new CmdExecutor(), "ultikits.tools.command.gm", this.i18n("游戏模式切换功能"), "gm");
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
        return "GMChanger";
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }

    public static GMChanger getInstance() {
        return gmChanger;
    }
}
