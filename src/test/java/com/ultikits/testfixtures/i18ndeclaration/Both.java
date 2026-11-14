package com.ultikits.testfixtures.i18ndeclaration;

import com.ultikits.testfixtures.bootlanguage.BootFixturePlugin;
import com.ultikits.ultitools.annotations.I18n;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/** Jar-loaded module fixture for obsolete language declarations. */
@SuppressWarnings({"deprecation", "removal"})
@I18n({"en", "fr"})
@UltiToolsModule(i18n = {"en", "fr", "ja"})
public class Both extends BootFixturePlugin {
    public Both(String version, String folder) {
        super(version, folder);
    }
}
