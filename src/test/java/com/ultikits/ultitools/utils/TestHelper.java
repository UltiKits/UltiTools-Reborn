package com.ultikits.ultitools.utils;

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
            // If mocking final class fails, we might need another approach,
            // but let's try this first.
            // If it fails, it will throw an exception and fail the test, which is what we want.
            throw new IllegalStateException("Failed to mock UltiTools instance", e);
        }
    }
}
