package com.ultikits.testfixtures.i18ndeclaration;

import com.ultikits.testfixtures.bootlanguage.BootFixturePlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/** Jar-loaded module fixture for obsolete language declarations. */
@SuppressWarnings({"deprecation", "removal"})
@UltiToolsModule(i18n = {"en", "fr", "ja"}, config = false, cmdExecutor = false, eventListener = false)
public class Aliased extends BootFixturePlugin {
    public Aliased(String version, String folder) {
        super(version, folder);
    }
}
