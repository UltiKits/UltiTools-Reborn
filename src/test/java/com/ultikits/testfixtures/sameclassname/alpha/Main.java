package com.ultikits.testfixtures.sameclassname.alpha;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/** A module whose ordinary main-class name is shared by another package. */
public class Main extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        return true;
    }
}
