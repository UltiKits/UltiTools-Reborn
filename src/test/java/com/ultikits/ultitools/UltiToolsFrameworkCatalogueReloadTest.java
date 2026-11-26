package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.ManagerKeys;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.internal.ConfiguredLocaleResolver;
import com.ultikits.ultitools.text.internal.FrameworkMessages;
import com.ultikits.ultitools.text.internal.TextEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

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
            UltiTools.reloadFrameworkCatalogue(engine, logger);
            assertThat(PlainTextComponentSerializer.plainText().serialize(messagesApi.render(
                    ManagerKeys.RELOAD_SUMMARY_ALL, Locale.forLanguageTag(code), Arg.number("total", 1))))
                    .isEqualTo("OVERRIDE 1");
        }
    }

    @Test
    void missingEngineIsSafe() {
        assertThatCode(() -> UltiTools.reloadFrameworkCatalogue(null, Logger.getAnonymousLogger())).doesNotThrowAnyException();
    }

    @Test
    void fullReloadRefreshesOverridesBeforeModules() throws Exception {
        var engine = mock(TextEngine.class);
        when(engine.fallback()).thenReturn(Locale.ENGLISH);
        var modules = mock(PluginManager.class);
        var plugin = reloadablePlugin(engine, modules, Logger.getAnonymousLogger());
        when(modules.reloadAllAndReport()).thenReturn(List.of("done"));
        assertThat(plugin.reloadPluginsAndReport()).containsExactly("done");
        var order = inOrder(engine, modules);
        order.verify(engine).reloadLocaleConfiguration();
        order.verify(engine).reloadOverrides("ultitools");
        order.verify(modules).reloadAllAndReport();
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void fullReloadWarnsOnceAndStillReloadsModulesAfterUnexpectedOverrideFailure(String code) throws Exception {
        var locale = Locale.forLanguageTag(code);
        var engine = mock(TextEngine.class);
        when(engine.fallback()).thenReturn(locale);
        var failure = new IllegalStateException("test override read failed: <red>&c");
        doThrow(failure).when(engine).reloadOverrides("ultitools");
        var modules = mock(PluginManager.class);
        when(modules.reloadAllAndReport()).thenReturn(List.of("done"));
        var logger = mock(Logger.class);
        var plugin = reloadablePlugin(engine, modules, logger);

        try (var catalogue = new CommandMessageTestSupport(code);
             var ignored = FrameworkMessages.overrideForTests(catalogue.messages(), locale)) {
            assertThat(plugin.reloadPluginsAndReport()).containsExactly("done");
        }
        verify(modules).reloadAllAndReport();
        var warning = ArgumentCaptor.forClass(String.class);
        verify(logger).log(eq(Level.WARNING), warning.capture(), same(failure));
        assertThat(warning.getValue()).isEqualTo(code.equals("en")
                ? "Could not reread the framework message override files; keeping the loaded messages and continuing to reload modules: test override read failed: <red>&c"
                : "无法重读框架消息覆盖文件，将保留已加载的消息并继续重载模块：test override read failed: <red>&c");
        verifyNoMoreInteractions(logger);
    }

    @Test
    void fullReloadDoesNotSwallowErrorsFromOverrideReload() throws Exception {
        var engine = mock(TextEngine.class);
        when(engine.fallback()).thenReturn(Locale.ENGLISH);
        var failure = new AssertionError("test override error");
        doThrow(failure).when(engine).reloadOverrides("ultitools");
        var modules = mock(PluginManager.class);
        var plugin = reloadablePlugin(engine, modules, Logger.getAnonymousLogger());

        assertThatThrownBy(plugin::reloadPluginsAndReport).isSameAs(failure);
        verifyNoInteractions(modules);
    }

    @Test
    void fullReloadFallsBackToBundledTextWhenOverrideIsMalformed() throws Exception {
        var logger = mock(Logger.class);
        assertThat(reloadAfterEditingOverride(logger, file -> Files.writeString(file, "reload: [")))
                .containsExactly("All 1 modules reloaded.");
        var warning = ArgumentCaptor.forClass(String.class);
        verify(logger).warning(warning.capture());
        assertThat(warning.getValue()).contains("en.yml");
    }

    @Test
    void fullReloadFallsBackToBundledTextWhenOverrideIsDeleted() throws Exception {
        assertThat(reloadAfterEditingOverride(Logger.getAnonymousLogger(), Files::delete))
                .containsExactly("All 1 modules reloaded.");
    }

    @FunctionalInterface
    private interface OverrideEdit {
        void apply(Path file) throws IOException;
    }

    private List<String> reloadAfterEditingOverride(Logger logger, OverrideEdit edit) throws Exception {
        var messages = Files.createDirectories(temp.resolve("messages"));
        var override = messages.resolve("en.yml");
        Files.writeString(override, "reload:\n  summary:\n    all: \"OVERRIDE <total:'0'>\"\n");
        var resolver = new ConfiguredLocaleResolver(() -> "auto", () -> "en", logger);
        try (var engine = new TextEngine(resolver, logger,
                UltiTools.class.getProtectionDomain().getCodeSource().getLocation(), messages)) {
            var modules = mock(PluginManager.class);
            var messagesApi = engine.messagesFor("ultitools");
            when(modules.reloadAllAndReport()).thenAnswer(invocation -> List.of(
                    PlainTextComponentSerializer.plainText().serialize(messagesApi.render(
                            ManagerKeys.RELOAD_SUMMARY_ALL, Locale.ENGLISH, Arg.number("total", 1)))));
            var plugin = reloadablePlugin(engine, modules, logger);
            assertThat(plugin.reloadPluginsAndReport()).containsExactly("OVERRIDE 1");
            edit.apply(override);
            var summary = plugin.reloadPluginsAndReport();
            verify(modules, times(2)).reloadAllAndReport();
            return summary;
        }
    }

    private UltiTools reloadablePlugin(TextEngine engine, PluginManager modules, Logger logger) throws Exception {
        var plugin = mock(UltiTools.class);
        var managers = mock(DependenceManagers.class);
        var context = mock(SimpleContainer.class);
        var config = new YamlConfiguration();
        config.set("language", "en");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getDependenceManagers()).thenReturn(managers);
        when(managers.getContext()).thenReturn(context);
        when(context.getBean(TextEngine.class)).thenReturn(engine);
        var field = UltiTools.class.getDeclaredField("pluginManager");
        field.setAccessible(true);
        field.set(plugin, modules);
        doCallRealMethod().when(plugin).reloadPluginsAndReport();
        return plugin;
    }
}
