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
 * {@link #vaultInstalled()} exists for exactly this reason, returning a plain {@code boolean}.
 * <p>
 * Every public method re-checks Vault's presence and the registered provider on every call — the
 * state is never cached — because Vault, and the economy plugin that registers the provider, can
 * load after this framework does; Bukkit does not guarantee plugin load order beyond declared
 * dependencies. {@link #vaultInstalled()} guards every method both against a live
 * {@code Bukkit.getServer()} not existing yet at all (measured: {@code DependenceManagersTest}
 * invokes the real {@code initCoreServices()} — which now wires this class in — against a bare
 * Mockito {@code JavaPlugin} with no {@code Bukkit.setServer(...)} call, exactly the environment
 * this guard exists for) and against no plugin named {@code "Vault"} being installed at all
 * (Codex P1, PR #463: {@code Economy.class} is a {@code provided}-scope Vault type — on a live
 * server missing Vault's jar, evaluating that class literal throws {@link NoClassDefFoundError}
 * before the {@code registration == null} fallback check below it ever runs; a live-but-non-null
 * {@code Bukkit.getServer()} alone says nothing about whether Vault itself is on the classpath).
 * {@link #vaultInstalled()} is the single guard every method below calls — see its own javadoc for
 * why that call must happen strictly before the {@code Economy.class} reference, never sharing an
 * expression with it, and why this class does not instead have a shared private helper that
 * returns an {@code Economy}/{@code RegisteredServiceProvider<Economy>}-typed value.
 *
 * @author wisdomme
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class VaultEconomyProvider implements EconomyProvider {

    private static final String VAULT_PLUGIN_NAME = "Vault";

    /**
     * The single guard every method below calls, strictly before referencing {@code Economy.class}
     * anywhere in that method — never in a shared expression evaluated ahead of this check, since a
     * {@code ldc} class-literal reference resolves lazily at the bytecode site where it executes,
     * so guarding here is exactly what keeps that reference unreached (and therefore unresolved)
     * when Vault is absent.
     * <p>
     * Deliberately a plain {@code boolean}, not a shared private helper returning
     * {@code Economy}/{@code RegisteredServiceProvider<Economy>} — see the class javadoc for why a
     * Vault-typed return here would put a Vault-typed signature back on this class and need the
     * very {@code SoftDependencySignatureInvariantTest} allowlist entry this design avoids.
     *
     * @return {@code false} when no live {@link org.bukkit.Server} is set yet, or when no plugin
     *         named {@code "Vault"} is installed — every method below treats both cases identically,
     *         as Vault being unavailable
     */
    private static boolean vaultInstalled() {
        return Bukkit.getServer() != null && Bukkit.getPluginManager().getPlugin(VAULT_PLUGIN_NAME) != null;
    }

    @Override
    public State getState() {
        if (!vaultInstalled()) {
            return State.VAULT_NOT_INSTALLED;
        }
        return Bukkit.getServicesManager().getRegistration(Economy.class) == null
                ? State.NO_PROVIDER_REGISTERED
                : State.AVAILABLE;
    }

    @Override
    public String getProviderName() {
        if (!vaultInstalled()) {
            return null;
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? null : registration.getProvider().getName();
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        if (!vaultInstalled()) {
            return 0;
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? 0 : registration.getProvider().getBalance(player);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        if (!vaultInstalled()) {
            return false;
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration != null && registration.getProvider().has(player, amount);
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        if (amount <= 0 || !vaultInstalled()) {
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
        if (amount <= 0 || !vaultInstalled()) {
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
        if (!vaultInstalled()) {
            return String.format("%.2f", amount);
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? String.format("%.2f", amount) : registration.getProvider().format(amount);
    }

    @Override
    public String getCurrencyNameSingular() {
        if (!vaultInstalled()) {
            return "coins";
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? "coins" : registration.getProvider().currencyNameSingular();
    }

    @Override
    public String getCurrencyNamePlural() {
        if (!vaultInstalled()) {
            return "coins";
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? "coins" : registration.getProvider().currencyNamePlural();
    }
}
