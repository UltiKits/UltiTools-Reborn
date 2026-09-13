package com.ultikits.ultitools.utils;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.services.EconomyProvider;
import com.ultikits.ultitools.services.impl.VaultEconomyProvider;

/**
 * Utility class for economy operations using Vault.
 * Provides convenient static methods for common economy operations.
 * <p>
 * <b>{@code @ApiStatus.Internal} note (D-08/D-09, #451, 6.3.0):</b> this class's own PUBLIC
 * signatures — including {@link #getEconomy()}'s Vault-typed return — are frozen for backward
 * compatibility: six modules already call this façade directly, and this plan changes none of
 * their call sites. That is the one deliberate exception to the framework's structural guard
 * against soft-dependency types in reflected signatures
 * ({@code buildtools.SoftDependencySignatureInvariantTest}) — this class carries its own explicit
 * allowlist entry there, with this paragraph as the one-line reason required of every entry.
 * <p>
 * Internally, every operation now delegates to the internal {@link EconomyProvider} seam and
 * reports the economy's unavailability honestly (D-08): the server's console gets exactly one
 * {@code WARNING} per calling module per server session, naming the module, distinguishing
 * "Vault is not installed" from "Vault is installed but no provider is registered", stating this
 * is the server operator's environment rather than a framework or module defect, and giving the
 * install instruction. This is a server-console line — it is never routed to the panel, since it
 * names which modules are installed.
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public final class EconomyUtils {

    /** Attribution key used when no registered module's package appears anywhere on the calling stack. */
    static final String UNKNOWN_MODULE = "an unknown caller";

    // Legacy direct-Vault state, kept ONLY to back getEconomy()'s pre-existing, unchanged public
    // contract of returning the actual underlying Vault Economy instance.
    private static Economy economy;
    private static boolean setupAttempted = false;

    // The internal seam every operation below delegates to (D-08/D-09). Framework bootstrap
    // (DependenceManagers) replaces this with the same instance it registers into the IoC
    // container via setProvider(); the field starts non-null so a module calling EconomyUtils
    // before the framework finishes booting (or in a test with no live UltiTools instance) still
    // gets a safe, real answer rather than a NullPointerException.
    private static volatile EconomyProvider provider = new VaultEconomyProvider();

    private static final Set<String> warnedModules = Collections.synchronizedSet(new LinkedHashSet<>());

    private EconomyUtils() {
        // Utility class
    }

    /**
     * Framework-internal wiring point — called once by {@code DependenceManagers} at bootstrap
     * with the same {@link EconomyProvider} instance it registers into the IoC container. Not
     * meant to be called by module code; public only because {@code DependenceManagers} lives in
     * another package.
     *
     * @param provider the active economy provider
     */
    public static void setProvider(EconomyProvider provider) {
        EconomyUtils.provider = provider != null ? provider : new VaultEconomyProvider();
    }

    /**
     * Logs the framework's one start-up line naming the current economy service state (D-08).
     * Framework-internal — called once by {@code DependenceManagers} after {@link #setProvider}.
     */
    public static void logStartupState() {
        EconomyProvider.State state = provider.getState();
        String message;
        switch (state) {
            case VAULT_NOT_INSTALLED:
                message = "[UltiTools-API] Vault not installed - economy service unavailable.";
                break;
            case NO_PROVIDER_REGISTERED:
                message = "[UltiTools-API] Vault detected, but no economy provider is registered - "
                        + "economy service unavailable.";
                break;
            case AVAILABLE:
            default:
                message = "[UltiTools-API] Hooked into Vault, economy provider: " + provider.getProviderName() + ".";
                break;
        }
        log(message, true);
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
        return provider.getState() == EconomyProvider.State.AVAILABLE;
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
        reportIfUnavailable();
        return provider.getBalance(player);
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
        reportIfUnavailable();
        return provider.has(player, amount);
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
        reportIfUnavailable();
        return provider.deposit(player, amount);
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
        reportIfUnavailable();
        return provider.withdraw(player, amount);
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
        if (amount <= 0) {
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
        reportIfUnavailable();
        return provider.format(amount);
    }

    /**
     * Gets the currency name (singular).
     *
     * @return the currency name
     */
    public static String getCurrencyName() {
        reportIfUnavailable();
        return provider.getCurrencyNameSingular();
    }

    /**
     * Gets the currency name (plural).
     *
     * @return the currency name (plural)
     */
    public static String getCurrencyNamePlural() {
        reportIfUnavailable();
        return provider.getCurrencyNamePlural();
    }

    /**
     * Resets the economy setup state. Used primarily for testing.
     */
    public static void reset() {
        economy = null;
        setupAttempted = false;
        warnedModules.clear();
        provider = new VaultEconomyProvider();
    }

    // === D-08 honest reporting ===

    /**
     * Called by every public operation above before delegating to {@link #provider}. Reports at
     * most once per calling module per server session, when the economy is unavailable — a
     * request made while it IS available is always silent and never touches {@link #warnedModules}.
     */
    private static void reportIfUnavailable() {
        if (provider.getState() == EconomyProvider.State.AVAILABLE) {
            return;
        }
        reportEconomyStateIfUnavailable(attributeCallingModule());
    }

    /**
     * The WARN/dedup logic, taking the calling module's name directly rather than resolving it
     * from the live call stack. Package-private so {@code EconomyUtilsReportingTest} can drive the
     * seven D-08 behaviours directly, without needing a fully-registered module fixture just to
     * exercise dedup/state-text logic that does not semantically depend on how the module's name
     * was obtained. {@link #reportIfUnavailable()} is the one real call site, which resolves the
     * module name from the live stack via {@link #attributeCallingModule()}.
     *
     * @param moduleName the calling module's name, or {@code null} for an unattributed caller
     *                    (behavior: a request whose stack contains no registered module package
     *                    still logs once, attributed to {@link #UNKNOWN_MODULE}, and never throws)
     */
    static void reportEconomyStateIfUnavailable(String moduleName) {
        EconomyProvider.State state = provider.getState();
        if (state == EconomyProvider.State.AVAILABLE) {
            return;
        }
        String dedupKey = moduleName == null ? UNKNOWN_MODULE : moduleName;
        if (!warnedModules.add(dedupKey)) {
            return;
        }
        log(buildWarningMessage(dedupKey, state), false);
    }

    private static String buildWarningMessage(String moduleName, EconomyProvider.State state) {
        String cause = state == EconomyProvider.State.VAULT_NOT_INSTALLED
                ? "Vault is not installed on this server"
                : "Vault is installed, but no economy provider is registered with it";
        return "[UltiTools-API] Module '" + moduleName + "' requested the economy service, but "
                + cause + ". This is the server's environment, not a defect in UltiTools or in '"
                + moduleName + "'. Install Vault and an economy plugin (e.g. EssentialsX) that "
                + "registers a Vault economy provider to enable economy features.";
    }

    private static void log(String message, boolean info) {
        UltiTools instance = UltiTools.getInstance();
        if (instance != null && instance.getLogger() != null) {
            if (info) {
                instance.getLogger().info(message);
            } else {
                instance.getLogger().warning(message);
            }
            return;
        }
        // No live UltiTools instance -- fall back to Bukkit's own logger, but only when a live
        // Server actually exists (Bukkit.getLogger() dereferences it internally too). Without
        // either, there is nowhere safe left to log to; silently drop rather than throw --
        // "never throws" is one of D-08's own truths. Measured: DependenceManagersTest invokes
        // the real initCoreServices() against a bare Mockito JavaPlugin with no live Server at
        // all, exactly this case.
        if (Bukkit.getServer() == null) {
            return;
        }
        if (info) {
            Bukkit.getLogger().info(message);
        } else {
            Bukkit.getLogger().warning(message);
        }
    }

    /**
     * The module-attribution helper (D-08): the first stack frame belonging to a registered
     * module's own scan packages, using the same {@link Thread#getStackTrace()} idiom
     * {@code SystemLogHandler} already relies on for trigger inference — no {@code StackWalker},
     * since this project's bytecode target is Java 8.
     *
     * @return the attributed module's name, or {@code null} when nothing on the framework's own
     *         plugin list is currently reachable (no live {@link UltiTools} instance, no plugin
     *         manager, or no registered module's scan package appears anywhere on the stack)
     */
    private static String attributeCallingModule() {
        UltiTools instance = UltiTools.getInstance();
        if (instance == null) {
            return null;
        }
        PluginManager pluginManager = instance.getPluginManager();
        if (pluginManager == null) {
            return null;
        }
        Map<String, String> prefixToModule = new LinkedHashMap<>();
        for (UltiToolsPlugin plugin : pluginManager.getPluginList()) {
            for (String pkg : pluginManager.getPluginScanPackages(plugin.getClass())) {
                prefixToModule.putIfAbsent(pkg, plugin.getPluginName());
            }
        }
        return attributeModule(Thread.currentThread().getStackTrace(), prefixToModule);
    }

    /**
     * Pure: given a stack trace and a map from package prefix to module name, returns the module
     * name of the first frame whose class name starts with one of the map's prefixes, or
     * {@code null} when no frame matches — including the empty-input cases (a null/empty stack, or
     * an empty prefix map, as when no module is registered yet). Package-private so
     * {@code EconomyUtilsReportingTest} can unit-test the attribution logic itself with synthetic
     * input, independent of a live {@link UltiTools} instance or real registered modules.
     *
     * @param stack          the stack trace to search, most-recent frame first
     * @param prefixToModule package prefix to module name, in preference order
     * @return the attributed module name, or {@code null} when nothing matches
     */
    static String attributeModule(StackTraceElement[] stack, Map<String, String> prefixToModule) {
        if (stack == null || prefixToModule == null || prefixToModule.isEmpty()) {
            return null;
        }
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            for (Map.Entry<String, String> entry : prefixToModule.entrySet()) {
                if (className.startsWith(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }
}
