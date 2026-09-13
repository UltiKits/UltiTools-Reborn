package com.ultikits.ultitools.services.impl;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.services.EconomyProvider;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

/**
 * The Vault consumer bridge — {@link EconomyProvider}'s only implementation in 6.3.0 (D-08/D-09).
 * <p>
 * Every {@code net.milkbowl.vault} reference in this class lives inside a method body as a local
 * variable, never as a declared return type, parameter type, or field type — none of this class's
 * own methods need an allowlist entry in {@code buildtools.SoftDependencySignatureInvariantTest}'s
 * structural guard, unlike {@code EconomyUtils} (which keeps a genuine Vault-typed public
 * signature for backward compatibility and therefore does need one). This split is deliberate:
 * it is exactly what lets the container reflect over this class — and every other class in the
 * framework — without the eager {@link Class#getDeclaredMethods()} signature resolution that
 * caused #451, while still concentrating every Vault type reference in one small, obviously-named
 * class instead of scattering it across the framework. Deliberately no shared private helper
 * returning {@code Economy}/{@code RegisteredServiceProvider<Economy>} either — that would put a
 * Vault-typed signature back on this class and need the very allowlist entry this design avoids;
 * {@link #bukkitReady()} exists for exactly this reason, returning a plain {@code boolean}.
 * <p>
 * Every public method re-checks Vault's presence and the registered provider on every call — the
 * state is never cached — because Vault, and the economy plugin that registers the provider, can
 * load after this framework does; Bukkit does not guarantee plugin load order beyond declared
 * dependencies. {@link #bukkitReady()} additionally guards every method against a live
 * {@code Bukkit.getServer()} not existing yet at all (measured: {@code DependenceManagersTest}
 * invokes the real {@code initCoreServices()} — which now wires this class in — against a bare
 * Mockito {@code JavaPlugin} with no {@code Bukkit.setServer(...)} call, exactly the environment
 * this guard exists for).
 *
 * @author wisdomme
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class VaultEconomyProvider implements EconomyProvider {

    private static final String VAULT_PLUGIN_NAME = "Vault";

    /**
     * @return {@code false} when no live {@link org.bukkit.Server} is set yet — every other
     *         method below treats this exactly like Vault being absent, since nothing Vault-shaped
     *         can possibly be observed without a live server either way
     */
    private static boolean bukkitReady() {
        return Bukkit.getServer() != null;
    }

    @Override
    public State getState() {
        if (!bukkitReady() || Bukkit.getPluginManager().getPlugin(VAULT_PLUGIN_NAME) == null) {
            return State.VAULT_NOT_INSTALLED;
        }
        return Bukkit.getServicesManager().getRegistration(Economy.class) == null
                ? State.NO_PROVIDER_REGISTERED
                : State.AVAILABLE;
    }

    @Override
    public String getProviderName() {
        if (!bukkitReady()) {
            return null;
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? null : registration.getProvider().getName();
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        if (!bukkitReady()) {
            return 0;
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? 0 : registration.getProvider().getBalance(player);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        if (!bukkitReady()) {
            return false;
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration != null && registration.getProvider().has(player, amount);
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        if (amount <= 0 || !bukkitReady()) {
            return false;
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            return false;
        }
        EconomyResponse response = registration.getProvider().depositPlayer(player, amount);
        return response.transactionSuccess();
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (amount <= 0 || !bukkitReady()) {
            return false;
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null || !registration.getProvider().has(player, amount)) {
            return false;
        }
        EconomyResponse response = registration.getProvider().withdrawPlayer(player, amount);
        return response.transactionSuccess();
    }

    @Override
    public String format(double amount) {
        if (!bukkitReady()) {
            return String.format("%.2f", amount);
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? String.format("%.2f", amount) : registration.getProvider().format(amount);
    }

    @Override
    public String getCurrencyNameSingular() {
        if (!bukkitReady()) {
            return "coins";
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? "coins" : registration.getProvider().currencyNameSingular();
    }

    @Override
    public String getCurrencyNamePlural() {
        if (!bukkitReady()) {
            return "coins";
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? "coins" : registration.getProvider().currencyNamePlural();
    }
}
