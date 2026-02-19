package com.ultikits.ultitools.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.util.Arrays;
import java.util.logging.Logger;

import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class UltiToolsAPITest {
    private JavaPlugin mockPlugin;

    @BeforeEach
    void setUp() {
        mockPlugin = mock(JavaPlugin.class);
        PluginDescriptionFile desc = mock(PluginDescriptionFile.class);
        when(mockPlugin.getName()).thenReturn("TestPlugin");
        when(mockPlugin.getDescription()).thenReturn(desc);
        when(desc.getVersion()).thenReturn("1.0.0");
        when(desc.getAuthors()).thenReturn(Arrays.asList("Author"));
        when(desc.getMain()).thenReturn("com.example.TestPlugin");
        when(mockPlugin.getDataFolder()).thenReturn(new File("/tmp/TestPlugin"));
        when(mockPlugin.getLogger()).thenReturn(Logger.getLogger("test"));

        // Reset state between tests
        UltiToolsAPI.reset();
    }

    @Test
    void isConnected_returnsFalseWhenNotConnected() {
        assertThat(UltiToolsAPI.isConnected(mockPlugin)).isFalse();
    }

    @Test
    void registerAdapter_makesPluginConnected() {
        UltiToolsAPI.registerAdapter(mockPlugin, new ExternalPluginAdapter(mockPlugin));
        assertThat(UltiToolsAPI.isConnected(mockPlugin)).isTrue();
    }

    @Test
    void registerAdapter_twice_isIdempotent() {
        UltiToolsAPI.registerAdapter(mockPlugin, new ExternalPluginAdapter(mockPlugin));
        UltiToolsAPI.registerAdapter(mockPlugin, new ExternalPluginAdapter(mockPlugin));
        assertThat(UltiToolsAPI.isConnected(mockPlugin)).isTrue();
    }

    @Test
    void removeAdapter_disconnectsPlugin() {
        UltiToolsAPI.registerAdapter(mockPlugin, new ExternalPluginAdapter(mockPlugin));
        assertThat(UltiToolsAPI.isConnected(mockPlugin)).isTrue();

        UltiToolsAPI.removeAdapter(mockPlugin);
        assertThat(UltiToolsAPI.isConnected(mockPlugin)).isFalse();
    }

    @Test
    void removeAdapter_nonConnected_doesNotThrow() {
        assertThatCode(() -> UltiToolsAPI.removeAdapter(mockPlugin)).doesNotThrowAnyException();
    }

    @Test
    void getAdapter_returnsCorrectAdapter() {
        ExternalPluginAdapter adapter = new ExternalPluginAdapter(mockPlugin);
        UltiToolsAPI.registerAdapter(mockPlugin, adapter);
        assertThat(UltiToolsAPI.getAdapter(mockPlugin)).isSameAs(adapter);
    }

    @Test
    void getAdapter_returnsNull_whenNotConnected() {
        assertThat(UltiToolsAPI.getAdapter(mockPlugin)).isNull();
    }

    @Test
    void connect_withoutUltiTools_throwsIllegalState() {
        // UltiTools.getInstance() is null in test environment
        assertThatThrownBy(() -> UltiToolsAPI.connect(mockPlugin))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UltiTools is not loaded");
    }

    @Test
    void disconnect_nonConnected_doesNotThrow() {
        assertThatCode(() -> UltiToolsAPI.disconnect(mockPlugin)).doesNotThrowAnyException();
    }

    @Test
    void onPluginDisable_disconnectsConnectedPlugin() {
        ExternalPluginAdapter adapter = new ExternalPluginAdapter(mockPlugin);
        UltiToolsAPI.registerAdapter(mockPlugin, adapter);
        assertThat(UltiToolsAPI.isConnected(mockPlugin)).isTrue();

        UltiToolsAPI.onPluginDisable(mockPlugin);
        assertThat(UltiToolsAPI.isConnected(mockPlugin)).isFalse();
    }

    @Test
    void onPluginDisable_ignoresNonConnectedPlugin() {
        assertThatCode(() -> UltiToolsAPI.onPluginDisable(mockPlugin)).doesNotThrowAnyException();
    }

    @Test
    void reset_clearsAllAdapters() {
        UltiToolsAPI.registerAdapter(mockPlugin, new ExternalPluginAdapter(mockPlugin));
        UltiToolsAPI.reset();
        assertThat(UltiToolsAPI.isConnected(mockPlugin)).isFalse();
    }
}
