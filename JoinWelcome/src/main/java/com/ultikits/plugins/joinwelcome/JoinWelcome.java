package com.ultikits.plugins.joinwelcome;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public final class JoinWelcome extends UltiToolsPlugin {


    @Override
    public boolean registerSelf() throws IOException {
        return false;
    }

    @Override
    public void unregisterSelf() {

    }

    @Override
    public String pluginName() {
        return "UltiTools-Home";
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
