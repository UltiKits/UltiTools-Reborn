package com.ultikits.plugins.menu;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

import java.util.Arrays;
import java.util.List;

@UltiToolsModule
public class PluginMain extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        return true;
    }

    @Override
    public void unregisterSelf() {
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }
}
