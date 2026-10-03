package com.ultikits.testfixtures.economyattribution.ownpackage.commands;

import com.ultikits.ultitools.utils.EconomyUtils;

/** The economy request of {@code OwnPackageModule}, from a package its declared root does not cover. */
public final class OwnPackageCaller {

    private OwnPackageCaller() {
    }

    public static boolean requestEconomy() {
        return EconomyUtils.isAvailable();
    }
}
