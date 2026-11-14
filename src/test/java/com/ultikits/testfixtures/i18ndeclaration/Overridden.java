package com.ultikits.testfixtures.i18ndeclaration;

import java.util.Arrays;
import java.util.List;

import com.ultikits.testfixtures.bootlanguage.BootFixturePlugin;
import com.ultikits.ultitools.annotations.I18n;

/** Jar-loaded module fixture for obsolete language declarations. */
@SuppressWarnings({"deprecation", "removal"})
@I18n({"en", "fr", "ja"})
public class Overridden extends BootFixturePlugin {
    public Overridden(String version, String folder) {
        super(version, folder);
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("en", "fr", "ja");
    }
}
