package com.ultikits.testfixtures.economyattribution.ownpackage;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * A module whose declared scan root ({@code .components}) does not cover its own package, where
 * its economy caller lives ({@code .commands}) -- #489.
 */
@UltiToolsModule(scanBasePackages = "com.ultikits.testfixtures.economyattribution.ownpackage.components")
public class OwnPackageModule extends UltiToolsPlugin {

    @Override
    public boolean registerSelf() {
        return true;
    }
}
