package com.ultikits.testfixtures.economyattribution.external;

import org.bukkit.plugin.java.JavaPlugin;

import com.ultikits.ultitools.utils.EconomyUtils;

/**
 * A plain Bukkit plugin that borrows the framework through the External Plugin API (#462). Its
 * scan package is this package; {@link #requestEconomy()} is its economy request.
 */
public class ExternalEconomyPlugin extends JavaPlugin {

    /** Asks whether the economy is available, as a real consumer does before touching a balance. */
    public static boolean requestEconomy() {
        return EconomyUtils.isAvailable();
    }
}
