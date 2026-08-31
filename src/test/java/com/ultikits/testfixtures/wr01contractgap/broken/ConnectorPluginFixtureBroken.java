package com.ultikits.testfixtures.wr01contractgap.broken;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * A real, concrete {@code JavaPlugin} subclass loaded through {@code MockBukkit.loadSimple} so
 * WR-01's {@code ExternalPluginIntegrationTest} coverage exercises the genuine
 * {@code registerExternal(...)} path -- including real Bukkit {@code CommandMap} registration --
 * rather than stopping short at a hand-mocked {@code JavaPlugin} that cannot reach that far.
 * Mirrors {@code com.ultikits.testfixtures.externalplugininjection.ConnectorPluginFixture}'s own
 * javadoc rationale (SILENT-16, #331): living in this package makes it double as the scan root
 * {@link org.bukkit.plugin.java.JavaPlugin#getDescription()}'s main-class derivation resolves to,
 * so {@code MockBukkit.loadSimple(ConnectorPluginFixtureBroken.class)}'s synthesized main class
 * puts {@link UnenforceableExternalCommandExecutor} -- the sibling fixture in this same package --
 * inside the same scan. Deliberately empty otherwise.
 *
 * @since 6.3.0
 */
public class ConnectorPluginFixtureBroken extends JavaPlugin {
}
