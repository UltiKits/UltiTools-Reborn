package com.ultikits.plugins.joinwelcome;

import com.ultikits.plugins.joinwelcome.config.Config;
import com.ultikits.plugins.joinwelcome.listener.JoinListener;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public final class JoinWelcome extends UltiToolsPlugin {
    private static JoinWelcome joinWelcome;

    public static JoinWelcome getJoinWelcome() {
        return joinWelcome;
    }

    @Override
    public boolean registerSelf() throws IOException {
        joinWelcome = this;
        getConfigManager().register(this, new Config("res/config/config.yml"));
        getListenerManager().register(this, new JoinListener());
        return true;
    }

    @Override
    public void unregisterSelf() {

    }

    @Override
    public String pluginName() {
        return "UltiTools-JoinWelcome";
    }

    @Override
    public int minUltiToolsVersion() {
        return 600;
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }
}
