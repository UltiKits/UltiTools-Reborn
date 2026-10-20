package com.ultikits.testfixtures.economyattribution.unknownfirst;

import com.ultikits.ultitools.utils.EconomyUtils;

/** An economy request from a package no registered module or connected plugin covers (#489). */
public final class UnknownFirstCaller {

    private UnknownFirstCaller() {
    }

    public static boolean requestEconomy() {
        return EconomyUtils.isAvailable();
    }
}
