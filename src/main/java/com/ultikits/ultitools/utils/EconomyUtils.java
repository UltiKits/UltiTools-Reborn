package com.ultikits.ultitools.utils;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Utility class for economy operations using Vault.
 * Provides convenient static methods for common economy operations.
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public final class EconomyUtils {
    
    private static Economy economy;
    private static boolean setupAttempted = false;
    
    private EconomyUtils() {
        // Utility class
    }
    
    /**
     * Sets up the economy provider from Vault.
     *
     * @return true if economy was set up successfully
     */
    public static boolean setup() {
        if (economy != null) {
            return true;
        }
        
        if (setupAttempted) {
            return false;
        }
        
        setupAttempted = true;
        
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        
        economy = rsp.getProvider();
        return economy != null;
    }
    
    /**
     * Checks if Vault economy is available.
     *
     * @return true if economy is available
     */
    public static boolean isAvailable() {
        return setup();
    }
    
    /**
     * Gets the economy instance.
     *
     * @return the economy instance, or null if not available
     */
    @Nullable
    public static Economy getEconomy() {
        setup();
        return economy;
    }
    
    /**
     * Gets the balance of a player.
     *
     * @param player the player
     * @return the balance, or 0 if economy is not available
     */
    public static double getBalance(OfflinePlayer player) {
        if (!setup()) {
            return 0;
        }
        return economy.getBalance(player);
    }
    
    /**
     * Gets the balance of a player by UUID.
     *
     * @param uuid the player's UUID
     * @return the balance, or 0 if economy is not available
     */
    public static double getBalance(UUID uuid) {
        return getBalance(Bukkit.getOfflinePlayer(uuid));
    }
    
    /**
     * Checks if a player has at least the specified amount.
     *
     * @param player the player
     * @param amount the amount to check
     * @return true if the player has at least the amount
     */
    public static boolean has(OfflinePlayer player, double amount) {
        if (!setup()) {
            return false;
        }
        return economy.has(player, amount);
    }
    
    /**
     * Checks if a player has at least the specified amount.
     *
     * @param uuid   the player's UUID
     * @param amount the amount to check
     * @return true if the player has at least the amount
     */
    public static boolean has(UUID uuid, double amount) {
        return has(Bukkit.getOfflinePlayer(uuid), amount);
    }
    
    /**
     * Deposits money into a player's account.
     *
     * @param player the player
     * @param amount the amount to deposit
     * @return true if the deposit was successful
     */
    public static boolean deposit(OfflinePlayer player, double amount) {
        if (!setup() || amount <= 0) {
            return false;
        }
        EconomyResponse response = economy.depositPlayer(player, amount);
        return response.transactionSuccess();
    }
    
    /**
     * Deposits money into a player's account.
     *
     * @param uuid   the player's UUID
     * @param amount the amount to deposit
     * @return true if the deposit was successful
     */
    public static boolean deposit(UUID uuid, double amount) {
        return deposit(Bukkit.getOfflinePlayer(uuid), amount);
    }
    
    /**
     * Withdraws money from a player's account.
     *
     * @param player the player
     * @param amount the amount to withdraw
     * @return true if the withdrawal was successful
     */
    public static boolean withdraw(OfflinePlayer player, double amount) {
        if (!setup() || amount <= 0) {
            return false;
        }
        if (!has(player, amount)) {
            return false;
        }
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        return response.transactionSuccess();
    }
    
    /**
     * Withdraws money from a player's account.
     *
     * @param uuid   the player's UUID
     * @param amount the amount to withdraw
     * @return true if the withdrawal was successful
     */
    public static boolean withdraw(UUID uuid, double amount) {
        return withdraw(Bukkit.getOfflinePlayer(uuid), amount);
    }
    
    /**
     * Transfers money from one player to another.
     *
     * @param from   the player to withdraw from
     * @param to     the player to deposit to
     * @param amount the amount to transfer
     * @return true if the transfer was successful
     */
    public static boolean transfer(OfflinePlayer from, OfflinePlayer to, double amount) {
        if (!setup() || amount <= 0) {
            return false;
        }
        if (!has(from, amount)) {
            return false;
        }
        if (!withdraw(from, amount)) {
            return false;
        }
        if (!deposit(to, amount)) {
            // Rollback
            deposit(from, amount);
            return false;
        }
        return true;
    }
    
    /**
     * Transfers money from one player to another.
     *
     * @param from   the UUID of the player to withdraw from
     * @param to     the UUID of the player to deposit to
     * @param amount the amount to transfer
     * @return true if the transfer was successful
     */
    public static boolean transfer(UUID from, UUID to, double amount) {
        return transfer(Bukkit.getOfflinePlayer(from), Bukkit.getOfflinePlayer(to), amount);
    }
    
    /**
     * Formats an amount according to the economy's format.
     *
     * @param amount the amount to format
     * @return the formatted amount string
     */
    public static String format(double amount) {
        if (!setup()) {
            return String.format("%.2f", amount);
        }
        return economy.format(amount);
    }
    
    /**
     * Gets the currency name (singular).
     *
     * @return the currency name
     */
    public static String getCurrencyName() {
        if (!setup()) {
            return "coins";
        }
        return economy.currencyNameSingular();
    }
    
    /**
     * Gets the currency name (plural).
     *
     * @return the currency name (plural)
     */
    public static String getCurrencyNamePlural() {
        if (!setup()) {
            return "coins";
        }
        return economy.currencyNamePlural();
    }
    
    /**
     * Resets the economy setup state. Used primarily for testing.
     */
    public static void reset() {
        economy = null;
        setupAttempted = false;
    }
}
