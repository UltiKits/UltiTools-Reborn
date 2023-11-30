package com.ultikits.plugins.home;

import com.ultikits.plugins.home.context.ContextConfig;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ContextEntry;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.annotations.I18n;
import lombok.Getter;

@EnableAutoRegister
@I18n({"zh", "en"})
@ContextEntry(ContextConfig.class)
public class PluginMain extends UltiToolsPlugin {
    @Getter
    private static PluginMain pluginMain;

    public PluginMain() {
        super();
        pluginMain = this;
    }

    @Override
    public boolean registerSelf() {
        return true;
    }

    @Override
    public void unregisterSelf() {
    }
}
