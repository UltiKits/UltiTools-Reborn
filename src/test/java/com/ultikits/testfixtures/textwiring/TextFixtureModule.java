package com.ultikits.testfixtures.textwiring;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/** Top-level so jar-backed child-first loading has no cross-loader nest host. */
public class TextFixtureModule extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        return true;
    }
}
