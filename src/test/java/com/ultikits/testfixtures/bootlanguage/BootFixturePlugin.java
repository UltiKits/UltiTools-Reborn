package com.ultikits.testfixtures.bootlanguage;

import java.util.Collections;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * The module class the start-up language fixture jar ships. Constructed through
 * {@link UltiToolsPlugin}'s connector constructor so a test controls the resource folder and the
 * version.
 */
public class BootFixturePlugin extends UltiToolsPlugin {

    /** Module name every instance reports; the resource folder is named after it. */
    public static final String MODULE = "BootModule";

    /** Main-class name every instance reports; the duplicate-version gate compares it. */
    public static final String MAIN_CLASS = "com.example.BootModule";

    public BootFixturePlugin(String version, String resourceFolderPath) {
        super(MODULE, version, Collections.emptyList(), Collections.emptyList(), 0, MAIN_CLASS,
                resourceFolderPath);
    }

    @Override
    public boolean registerSelf() {
        return true;
    }
}
