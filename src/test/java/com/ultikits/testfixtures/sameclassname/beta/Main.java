package com.ultikits.testfixtures.sameclassname.beta;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/** Independent module with the same simple main-class name as the alpha fixture. */
public class Main extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        return true;
    }
}
