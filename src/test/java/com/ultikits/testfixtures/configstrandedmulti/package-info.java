/**
 * Fixtures for the gate-1 CR-01 finding on plan 16-14 - #358 Part 1's stranded-entity fix must
 * be scoped to a plugin's WHOLE {@code initConfig()} run, not to a single {@code registerAll()}
 * call. {@code pkga} and {@code pkgb} are two SIBLING packages (neither a subpackage of the
 * other, so #362's de-duplication leaves both in {@code DependencyUtils.getPluginPackages}'s
 * result) - {@code pkga} holds one always-valid class, {@code pkgb} holds one class that always
 * violates its own {@code @Range} constraint. A module declaring both via {@code
 * @UltiToolsModule(scanBasePackages = {...})} reaches {@code UltiToolsPlugin.initConfig()}'s
 * loop, which calls {@code ConfigManager.registerAll} once per package - exactly the shape whose
 * per-call-only rollback left {@code pkga}'s entry stranded before the CR-01 fix.
 */
package com.ultikits.testfixtures.configstrandedmulti;
