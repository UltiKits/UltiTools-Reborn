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
 * <p>
 * 使用 Vault 进行经济操作的工具类。
 * 提供常用经济操作的便捷静态方法。
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
     * <p>
     * 从 Vault 设置经济提供者。
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
     * <p>
     * 检查 Vault 经济是否可用。
     *
     * @return true if economy is available
     */
    public static boolean isAvailable() {
        return setup();
    }
    
    /**
     * Gets the economy instance.
     * <p>
     * 获取经济实例。
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
     * <p>
     * 获取玩家余额。
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
     * <p>
     * 通过 UUID 获取玩家余额。
     *
     * @param uuid the player's UUID
     * @return the balance, or 0 if economy is not available
     */
    public static double getBalance(UUID uuid) {
        return getBalance(Bukkit.getOfflinePlayer(uuid));
    }
    
    /**
     * Checks if a player has at least the specified amount.
     * <p>
     * 检查玩家是否拥有至少指定数量的金钱。
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
     * <p>
     * 检查玩家是否拥有至少指定数量的金钱。
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
     * <p>
     * 向玩家账户存入金钱。
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
     * <p>
     * 向玩家账户存入金钱。
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
     * <p>
     * 从玩家账户取出金钱。
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
     * <p>
     * 从玩家账户取出金钱。
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
     * <p>
     * 在两个玩家之间转账。
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
     * <p>
     * 在两个玩家之间转账。
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
     * <p>
     * 根据经济插件的格式格式化金额。
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
     * <p>
     * 获取货币名称（单数形式）。
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
     * <p>
     * 获取货币名称（复数形式）。
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
     * <p>
     * 重置经济设置状态。主要用于测试。
     */
    public static void reset() {
        economy = null;
        setupAttempted = false;
    }
}
