package com.ultikits.testfixtures.i18ndeclaration;

import com.ultikits.testfixtures.bootlanguage.BootFixturePlugin;
import com.ultikits.ultitools.annotations.I18n;

/** Jar-loaded module fixture for obsolete language declarations. */
@SuppressWarnings({"deprecation", "removal"})
@I18n({"en", "fr", "ja", "fr"})
public class Direct extends BootFixturePlugin {
    public Direct(String version, String folder) {
        super(version, folder);
    }
}
