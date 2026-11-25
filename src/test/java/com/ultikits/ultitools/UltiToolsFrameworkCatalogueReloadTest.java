package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.ManagerKeys;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.internal.ConfiguredLocaleResolver;
import com.ultikits.ultitools.text.internal.TextEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class UltiToolsFrameworkCatalogueReloadTest {
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void reloadReadsChangedOperatorOverride(String code) throws Exception {
        Logger logger = Logger.getAnonymousLogger();
        var resolver = new ConfiguredLocaleResolver(() -> "auto", () -> code, logger);
        Path messages = Files.createDirectories(temp.resolve("messages"));
        try (var engine = new TextEngine(resolver, logger,
                UltiTools.class.getProtectionDomain().getCodeSource().getLocation(), messages)) {
            var messagesApi = engine.messagesFor("ultitools");
            assertThat(PlainTextComponentSerializer.plainText().serialize(messagesApi.render(
                    ManagerKeys.RELOAD_SUMMARY_ALL, Locale.forLanguageTag(code), Arg.number("total", 1))))
                    .isEqualTo(code.equals("en") ? "All 1 modules reloaded." : "已重载全部 1 个模块。");
            Files.writeString(messages.resolve(code + ".yml"), "reload:\n  summary:\n    all: \"OVERRIDE <total:'0'>\"\n");
            reload(engine);
            assertThat(PlainTextComponentSerializer.plainText().serialize(messagesApi.render(
                    ManagerKeys.RELOAD_SUMMARY_ALL, Locale.forLanguageTag(code), Arg.number("total", 1))))
                    .isEqualTo("OVERRIDE 1");
        }
    }

    @Test
    void missingEngineIsSafe() {
        assertThatCode(() -> reload(null)).doesNotThrowAnyException();
    }

    @Test
    void fullReloadRefreshesOverridesBeforeModules() throws Exception {
        var plugin = mock(UltiTools.class);
        var managers = mock(DependenceManagers.class);
        var context = mock(SimpleContainer.class);
        var engine = mock(TextEngine.class);
        var modules = mock(PluginManager.class);
        var config = new YamlConfiguration();
        config.set("language", "en");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getDependenceManagers()).thenReturn(managers);
        when(managers.getContext()).thenReturn(context);
        when(context.getBean(TextEngine.class)).thenReturn(engine);
        when(engine.fallback()).thenReturn(Locale.ENGLISH);
        when(modules.reloadAllAndReport()).thenReturn(List.of("done"));
        var field = UltiTools.class.getDeclaredField("pluginManager");
        field.setAccessible(true);
        field.set(plugin, modules);
        doCallRealMethod().when(plugin).reloadPluginsAndReport();
        assertThat(plugin.reloadPluginsAndReport()).containsExactly("done");
        var order = inOrder(engine, modules);
        order.verify(engine).reloadLocaleConfiguration();
        order.verify(engine).reloadOverrides("ultitools");
        order.verify(modules).reloadAllAndReport();
    }

    private static void reload(TextEngine engine) throws Exception {
        var method = UltiTools.class.getDeclaredMethod("reloadFrameworkCatalogue", TextEngine.class);
        method.setAccessible(true);
        method.invoke(null, engine);
    }
}
