package com.ultikits.ultitools.interfaces;

import java.io.IOException;

public interface IPlugin {

    boolean registerSelf() throws IOException;

    void unregisterSelf();

    String pluginName();
}
