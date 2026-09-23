package com.ultikits.testfixtures.configbinding531external;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * #531 fixture: a real {@code JavaPlugin} loaded through {@code MockBukkit.loadSimple}, whose
 * package is the External Plugin API scan root, so {@link BoundCooldownExternalCommandExecutor}
 * is found by the genuine {@code PluginManager.registerExternal(...)} path. Same shape as
 * {@code com.ultikits.testfixtures.wr01contractgap.broken.ConnectorPluginFixtureBroken}.
 * Deliberately empty otherwise.
 */
public class ConnectorPluginFixtureBoundCooldown extends JavaPlugin {
}
