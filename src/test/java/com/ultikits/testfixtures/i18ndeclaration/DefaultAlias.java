package com.ultikits.testfixtures.i18ndeclaration;

import com.ultikits.testfixtures.bootlanguage.BootFixturePlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/** Jar-loaded module fixture for obsolete language declarations. */
@SuppressWarnings({"deprecation", "removal"})
@UltiToolsModule
public class DefaultAlias extends BootFixturePlugin {
    public DefaultAlias(String version, String folder) {
        super(version, folder);
    }
}
