package com.ultikits.testfixtures.economyattribution.unknownsecond;

import com.ultikits.ultitools.utils.EconomyUtils;

/** An economy request from a package no registered module or connected plugin covers (#489). */
public final class UnknownSecondCaller {

    private UnknownSecondCaller() {
    }

    public static boolean requestEconomy() {
        return EconomyUtils.isAvailable();
    }
}
