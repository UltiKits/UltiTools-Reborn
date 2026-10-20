/**
 * Fixtures for {@code PluginManagerDependPrecheckTest} (#554, real-server acceptance): a module jar
 * whose {@code plugin.yml} lists a plugin under {@code depend:} that is not installed, whose main
 * class would extract resources when constructed, and which carries a scanned component built on a
 * type from that plugin -- the shape UltiEconomy has on a server without Vault, where the component
 * scans each logged a class-not-found trace before the module was refused.
 */
package com.ultikits.testfixtures.dependprecheck;
