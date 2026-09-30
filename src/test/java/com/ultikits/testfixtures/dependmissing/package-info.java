/**
 * Fixtures for {@code PluginManagerMissingDependencyLineTest} (#554): a module main class whose
 * loading fails with {@link NoClassDefFoundError} because a type from a plugin its
 * {@code plugin.yml} lists under {@code depend:} is not available -- the shape UltiEconomy has on a
 * server without Vault. {@link com.ultikits.testfixtures.dependmissing.AbsentEconomyApi} stands in
 * for Vault's {@code Economy}; the test's class loader refuses to load it.
 */
package com.ultikits.testfixtures.dependmissing;
