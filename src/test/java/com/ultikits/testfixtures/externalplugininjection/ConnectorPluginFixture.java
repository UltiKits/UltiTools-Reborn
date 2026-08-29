package com.ultikits.testfixtures.externalplugininjection;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * A real, concrete {@code JavaPlugin} subclass loaded through {@code MockBukkit.loadSimple}, so
 * "the connector's own concrete plugin class" is a genuine, compile-time-referenceable type --
 * unlike a Mockito mock, whose runtime class is a dynamically generated subclass no fixture could
 * declare a constructor parameter against. Stands in for a real UltiKits module's main class
 * (SILENT-16, #331). Deliberately empty: it exists only to be instantiated and injected, not to
 * exercise any behavior of its own. Living in this package makes it double as the scan root
 * {@code ExternalPluginAdapter.getScanPackage()} derives from {@code getMain()}, so
 * {@code MockBukkit.loadSimple(ConnectorPluginFixture.class)}'s synthesized main class puts every
 * sibling {@code @Service} fixture in this package inside the same scan.
 */
public class ConnectorPluginFixture extends JavaPlugin {
}
