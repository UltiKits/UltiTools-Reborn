package com.ultikits.ultitools.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.io.File;
import java.util.Arrays;
import java.util.logging.Logger;

import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ExternalPluginAdapterTest {
    private JavaPlugin mockPlugin;
    private ExternalPluginAdapter adapter;

    @BeforeEach
    void setUp() {
        mockPlugin = mock(JavaPlugin.class);
        PluginDescriptionFile desc = mock(PluginDescriptionFile.class);
        when(mockPlugin.getName()).thenReturn("TestExternalPlugin");
        when(mockPlugin.getDescription()).thenReturn(desc);
        when(desc.getVersion()).thenReturn("1.0.0");
        when(desc.getAuthors()).thenReturn(Arrays.asList("TestAuthor"));
        when(desc.getMain()).thenReturn("com.example.test.TestPlugin");
        when(mockPlugin.getDataFolder()).thenReturn(new File("/tmp/TestExternalPlugin"));
        when(mockPlugin.getLogger()).thenReturn(Logger.getLogger("TestExternalPlugin"));

        adapter = new ExternalPluginAdapter(mockPlugin);
    }

    @Test
    void getPluginName_returnsJavaPluginName() {
        assertThat(adapter.getPluginName()).isEqualTo("TestExternalPlugin");
    }

    @Test
    void getVersion_returnsJavaPluginVersion() {
        assertThat(adapter.getVersion()).isEqualTo("1.0.0");
    }

    @Test
    void getDataFolder_returnsJavaPluginDataFolder() {
        assertThat(adapter.getDataFolder()).isEqualTo(new File("/tmp/TestExternalPlugin"));
    }

    @Test
    void getPluginClassLoader_returnsNonNull() {
        // Mock's classloader is the test classloader — just verify it's not null
        assertThat(adapter.getPluginClassLoader()).isNotNull();
    }

    @Test
    void getJavaPlugin_returnsWrappedPlugin() {
        assertThat(adapter.getJavaPlugin()).isSameAs(mockPlugin);
    }

    @Test
    void getScanPackage_returnsMainClassPackage() {
        assertThat(adapter.getScanPackage()).isEqualTo("com.example.test");
    }

    @Test
    void getScanPackage_withDeepNesting() {
        JavaPlugin plugin2 = mock(JavaPlugin.class);
        PluginDescriptionFile desc2 = mock(PluginDescriptionFile.class);
        when(plugin2.getName()).thenReturn("DeepPlugin");
        when(plugin2.getDescription()).thenReturn(desc2);
        when(desc2.getVersion()).thenReturn("1.0.0");
        when(desc2.getAuthors()).thenReturn(Arrays.asList("Author"));
        when(desc2.getMain()).thenReturn("com.example.deep.nested.pkg.MyPlugin");
        when(plugin2.getDataFolder()).thenReturn(new File("/tmp/DeepPlugin"));
        when(plugin2.getLogger()).thenReturn(Logger.getLogger("DeepPlugin"));

        ExternalPluginAdapter adapter2 = new ExternalPluginAdapter(plugin2);
        assertThat(adapter2.getScanPackage()).isEqualTo("com.example.deep.nested.pkg");
    }

    @Test
    void getScanPackage_topLevelClass_returnsEmpty() {
        JavaPlugin plugin3 = mock(JavaPlugin.class);
        PluginDescriptionFile desc3 = mock(PluginDescriptionFile.class);
        when(plugin3.getName()).thenReturn("TopLevel");
        when(plugin3.getDescription()).thenReturn(desc3);
        when(desc3.getVersion()).thenReturn("1.0.0");
        when(desc3.getAuthors()).thenReturn(Arrays.asList("Author"));
        when(desc3.getMain()).thenReturn("TopLevelPlugin");
        when(plugin3.getDataFolder()).thenReturn(new File("/tmp/TopLevel"));
        when(plugin3.getLogger()).thenReturn(Logger.getLogger("TopLevel"));

        ExternalPluginAdapter adapter3 = new ExternalPluginAdapter(plugin3);
        assertThat(adapter3.getScanPackage()).isEqualTo("");
    }

    @Test
    void isConnected_defaultsFalse() {
        assertThat(adapter.isConnected()).isFalse();
    }

    @Test
    void setConnected_updatesState() {
        adapter.setConnected(true);
        assertThat(adapter.isConnected()).isTrue();
    }

    @Test
    void getLogger_delegatesToJavaPlugin() {
        Logger logger = adapter.getLogger();
        assertThat(logger).isNotNull();
    }

    @Test
    void getAuthors_returnsPluginAuthors() {
        assertThat(adapter.getAuthors()).containsExactly("TestAuthor");
    }

    @Test
    void getMainClass_returnsPluginMainClass() {
        assertThat(adapter.getMainClass()).isEqualTo("com.example.test.TestPlugin");
    }
}
