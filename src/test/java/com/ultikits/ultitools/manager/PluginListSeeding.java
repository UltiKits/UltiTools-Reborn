package com.ultikits.ultitools.manager;

import java.lang.reflect.Field;
import java.util.List;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * Test-only seeding of {@link PluginManager}'s loaded-module list.
 * <p>
 * Tests that need a module "already loaded" without driving a full registration used to call
 * {@code pluginManager.getPluginList().add(module)}. Since #507 {@link PluginManager#getPluginList()}
 * returns an unmodifiable snapshot, so that no production caller can mutate or race the internal
 * list; tests seed the internal list here instead, by reflection, which works against both the
 * list's old and new implementation.
 */
public final class PluginListSeeding {

    private PluginListSeeding() {
    }

    /**
     * Appends {@code plugin} to {@code manager}'s internal loaded-module list.
     *
     * @param manager the plugin manager to seed
     * @param plugin  the module to list as loaded
     */
    @SuppressWarnings({"unchecked", "PMD.AvoidAccessibilityAlteration"}) // test seam: the list is private by design
    public static void add(PluginManager manager, UltiToolsPlugin plugin) {
        try {
            Field field = PluginManager.class.getDeclaredField("pluginList");
            field.setAccessible(true);
            ((List<UltiToolsPlugin>) field.get(manager)).add(plugin);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("PluginManager.pluginList is not reachable", e);
        }
    }
}
