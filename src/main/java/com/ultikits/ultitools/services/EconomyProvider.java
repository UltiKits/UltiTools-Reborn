package com.ultikits.ultitools.services;

import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal economy-provider seam behind {@link com.ultikits.ultitools.utils.EconomyUtils}.
 * <p>
 * Introduced by D-08/D-09 (#451, 6.3.0) as the fix for a total bootstrap crash: with Vault
 * absent, {@code UltiTools#getEconomy()} used to be the only signature on the core class
 * mentioning a {@code net.milkbowl.vault} type, and reflecting over that class's declared
 * methods (done the instant the core plugin bean is registered) eagerly resolved that return
 * type, throwing {@link NoClassDefFoundError} before a single module loaded. Removing that
 * accessor broke the crash chain at the source; this interface is where the framework's economy
 * reporting now lives instead, one layer removed from the core class.
 * <p>
 * <b>{@code @ApiStatus.Internal} — not public API in 6.3.0.</b> Its only implementation this
 * release is the Vault consumer bridge ({@code services.impl.VaultEconomyProvider}). A public,
 * multi-currency framework economy interface is scoped for 6.4.0 (D-09); shipping one now and
 * reshaping it later would itself become a same-release-exception removal record, which this
 * milestone's own "6.3.0 adds no features" boundary forbids. Do not widen this interface's
 * visibility or add a second implementation without a fresh decision.
 * <p>
 * None of this interface's methods mention a Vault type in their own signature — every Vault
 * reference in the sole implementation lives inside method bodies as local variables, never as a
 * declared return or parameter type — so nothing here needs an allowlist entry in
 * {@code buildtools.SoftDependencySignatureInvariantTest}'s structural guard. {@link EconomyUtils}
 * is the one class in this seam that keeps a genuine Vault-typed signature (its pre-existing
 * public {@code getEconomy(): net.milkbowl.vault.economy.Economy}), because its own signatures
 * are frozen for backward compatibility with the six modules that already call it — that is why
 * {@code EconomyUtils} carries the allowlist entry instead.
 *
 * @author wisdomme
 * @since 6.3.0
 */
@ApiStatus.Internal
public interface EconomyProvider {

    /**
     * The three states an economy request can be in, named so the operator-facing message can
     * distinguish them (D-08): a missing plugin is not the same problem as a missing provider.
     */
    enum State {
        /** No plugin named {@code Vault} is installed on this server at all. */
        VAULT_NOT_INSTALLED,
        /** Vault is installed, but no economy plugin (e.g. EssentialsX) has registered a provider. */
        NO_PROVIDER_REGISTERED,
        /** Vault is installed and a provider is registered — economy operations work. */
        AVAILABLE
    }

    /**
     * The current state, re-evaluated on every call rather than cached — a provider can register
     * (or Vault can be installed) after this framework has already started, since plugin load
     * order is not guaranteed.
     *
     * @return the current economy-availability state
     */
    State getState();

    /**
     * The active provider's display name, for the framework's start-up log line.
     *
     * @return the provider's name, or {@code null} when {@link #getState()} is not
     *         {@link State#AVAILABLE}
     */
    String getProviderName();

    /**
     * @param player the player to query
     * @return the player's balance, or {@code 0} when unavailable
     */
    double getBalance(OfflinePlayer player);

    /**
     * @param player the player to query
     * @param amount the amount to check
     * @return {@code true} if the player has at least {@code amount}, {@code false} when
     *         unavailable or insufficient
     */
    boolean has(OfflinePlayer player, double amount);

    /**
     * @param player the player to credit
     * @param amount the amount to deposit
     * @return {@code true} if the deposit succeeded
     */
    boolean deposit(OfflinePlayer player, double amount);

    /**
     * @param player the player to debit
     * @param amount the amount to withdraw
     * @return {@code true} if the withdrawal succeeded
     */
    boolean withdraw(OfflinePlayer player, double amount);

    /**
     * @param amount the amount to format
     * @return the provider's own formatted string, or a plain two-decimal fallback when unavailable
     */
    String format(double amount);

    /**
     * @return the provider's singular currency name, or a fallback when unavailable
     */
    String getCurrencyNameSingular();

    /**
     * @return the provider's plural currency name, or a fallback when unavailable
     */
    String getCurrencyNamePlural();
}
