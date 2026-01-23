/**
 * Synced from UltiTools-API v6.2.0 - 2026-01-08
 * Source: src/test/java/com/ultikits/ultitools/utils/TestHelper.java
 * 
 * 如需更新，请从 UltiTools-Reborn 主项目同步此文件
 */
package com.ultikits.plugins.essentials.utils;

import java.lang.reflect.Field;

import org.mockito.Mockito;

import com.ultikits.ultitools.UltiTools;

@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test helper requires reflection for mocking
public class TestHelper {
    public static void mockUltiToolsInstance() {
        try {
            UltiTools mock = Mockito.mock(UltiTools.class);
            Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
            instanceField.setAccessible(true);
            instanceField.set(null, mock);

            // Mock i18n(String)
            Mockito.lenient().when(mock.i18n(Mockito.anyString())).thenAnswer(invocation -> invocation.getArgument(0));

            // Mock getServer()
            Mockito.lenient().when(mock.getServer()).thenAnswer(invocation -> org.bukkit.Bukkit.getServer());

            // Mock isEnabled()
            Mockito.lenient().when(mock.isEnabled()).thenReturn(true);

            // Mock getPluginLoader()
            org.bukkit.plugin.PluginLoader pluginLoader = Mockito.mock(org.bukkit.plugin.PluginLoader.class);
            Mockito.lenient().when(mock.getPluginLoader()).thenReturn(pluginLoader);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to mock UltiTools instance", e);
        }
    }
}
