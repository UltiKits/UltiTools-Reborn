package com.ultikits.ultitools.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.services.EconomyProvider;

import net.milkbowl.vault.economy.Economy;

/**
 * Codex P1 (PR #463 review thread on {@code VaultEconomyProvider.java:79}): every data method
 * used to guard only on {@link VaultEconomyProvider#getState()}'s sibling private check — a live
 * {@link Server} existing — before referencing the Vault-typed {@code Economy.class} literal via
 * {@code Bukkit.getServicesManager().getRegistration(Economy.class)}. Only {@link
 * VaultEconomyProvider#getState()} additionally checked that a plugin named {@code "Vault"} is
 * actually installed before touching anything Vault-shaped. On a live server with no Vault plugin,
 * evaluating that class literal resolves {@code net.milkbowl.vault.economy.Economy} for the first
 * time and throws {@link NoClassDefFoundError} instead of returning the documented fallback (see
 * {@link EconomyProvider}'s per-method javadoc: {@code 0} / {@code false} / {@code null} /
 * {@code "coins"} / a plain two-decimal format), because Vault's API jar is {@code provided} scope
 * (declared in {@code pom.xml}) and is only actually present on a live server's classpath when the
 * Vault plugin itself is installed there.
 * <p>
 * <b>Why this cannot be a plain fallback-value assertion.</b> Vault's API jar IS present on this
 * test's own compile/runtime classpath ({@code provided} dependencies still resolve for tests), so
 * {@code Economy.class} always resolves cleanly here regardless of whether the production guard
 * exists — a bare {@code assertThat(provider.getBalance(...)).isZero()} would pass identically
 * whether or not the bug was fixed, and does not reproduce the real-server crash at all. The only
 * way to prove the guard exists is behavioral: {@link Bukkit#getServicesManager()} is mocked so
 * every call to {@link ServicesManager#getRegistration(Class)} — the one call in each method that
 * evaluates the {@code Economy.class} literal — can be verified directly. Before the fix, every
 * data method below invokes it once even though Vault is absent (and would crash with
 * {@link NoClassDefFoundError} on a real server missing Vault's jar); after the fix, none of them
 * do, because the guard now checks Vault's presence before ever reaching that call.
 */
@DisplayName("VaultEconomyProvider — Vault-absent guard (Codex P1, PR #463)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class VaultEconomyProviderTest {

    private static final String VAULT_PLUGIN_NAME = "Vault";

    private VaultEconomyProvider provider;
    private MockedStatic<Bukkit> bukkitMock;
    private ServicesManager mockServicesManager;
    private OfflinePlayer mockPlayer;

    @BeforeEach
    void setUp() {
        provider = new VaultEconomyProvider();

        Server mockServer = mock(Server.class);
        PluginManager mockPluginManager = mock(PluginManager.class);
        mockServicesManager = mock(ServicesManager.class);
        mockPlayer = mock(OfflinePlayer.class);

        bukkitMock = mockStatic(Bukkit.class);
        bukkitMock.when(Bukkit::getServer).thenReturn(mockServer);
        bukkitMock.when(Bukkit::getPluginManager).thenReturn(mockPluginManager);
        bukkitMock.when(Bukkit::getServicesManager).thenReturn(mockServicesManager);

        // Vault absent: no plugin named "Vault" is installed on this (live, non-null) server.
        when(mockPluginManager.getPlugin(VAULT_PLUGIN_NAME)).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        if (bukkitMock != null) {
            bukkitMock.close();
        }
    }

    @Test
    @DisplayName("getBalance() returns the documented 0 fallback and never asks the services manager for Economy")
    void getBalance_vaultAbsent_returnsZeroFallback_neverLooksUpEconomy() {
        assertThat(provider.getBalance(mockPlayer)).isZero();
        verify(mockServicesManager, never()).getRegistration(Economy.class);
    }

    @Test
    @DisplayName("has() returns the documented false fallback and never asks the services manager for Economy")
    void has_vaultAbsent_returnsFalseFallback_neverLooksUpEconomy() {
        assertThat(provider.has(mockPlayer, 10.0)).isFalse();
        verify(mockServicesManager, never()).getRegistration(Economy.class);
    }

    @Test
    @DisplayName("deposit() returns the documented false fallback and never asks the services manager for Economy")
    void deposit_vaultAbsent_returnsFalseFallback_neverLooksUpEconomy() {
        assertThat(provider.deposit(mockPlayer, 10.0)).isFalse();
        verify(mockServicesManager, never()).getRegistration(Economy.class);
    }

    @Test
    @DisplayName("withdraw() returns the documented false fallback and never asks the services manager for Economy")
    void withdraw_vaultAbsent_returnsFalseFallback_neverLooksUpEconomy() {
        assertThat(provider.withdraw(mockPlayer, 10.0)).isFalse();
        verify(mockServicesManager, never()).getRegistration(Economy.class);
    }

    @Test
    @DisplayName("format() returns the documented two-decimal fallback and never asks the services manager for Economy")
    void format_vaultAbsent_returnsTwoDecimalFallback_neverLooksUpEconomy() {
        assertThat(provider.format(12.5)).isEqualTo("12.50");
        verify(mockServicesManager, never()).getRegistration(Economy.class);
    }

    @Test
    @DisplayName("getCurrencyNameSingular() returns the documented \"coins\" fallback and never asks the services manager for Economy")
    void getCurrencyNameSingular_vaultAbsent_returnsCoinsFallback_neverLooksUpEconomy() {
        assertThat(provider.getCurrencyNameSingular()).isEqualTo("coins");
        verify(mockServicesManager, never()).getRegistration(Economy.class);
    }

    @Test
    @DisplayName("getCurrencyNamePlural() returns the documented \"coins\" fallback and never asks the services manager for Economy")
    void getCurrencyNamePlural_vaultAbsent_returnsCoinsFallback_neverLooksUpEconomy() {
        assertThat(provider.getCurrencyNamePlural()).isEqualTo("coins");
        verify(mockServicesManager, never()).getRegistration(Economy.class);
    }

    @Test
    @DisplayName("getProviderName() returns the documented null fallback and never asks the services manager for Economy")
    void getProviderName_vaultAbsent_returnsNullFallback_neverLooksUpEconomy() {
        assertThat(provider.getProviderName()).isNull();
        verify(mockServicesManager, never()).getRegistration(Economy.class);
    }

    @Test
    @DisplayName("getState() already correctly reported VAULT_NOT_INSTALLED without touching the services manager (regression guard, not itself the P1)")
    void getState_vaultAbsent_reportsNotInstalled_neverLooksUpEconomy() {
        assertThat(provider.getState()).isEqualTo(EconomyProvider.State.VAULT_NOT_INSTALLED);
        verify(mockServicesManager, never()).getRegistration(Economy.class);
    }
}
