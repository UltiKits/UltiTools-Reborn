package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * D-01/D-02/D-03 (#419, #455): {@link UltiToolsPlugin#unregisterSelf()} and {@link
 * UltiToolsPlugin#reloadSelf()} become {@code final} template methods, each delegating to a
 * {@code protected} hook a module CAN override without being able to skip the framework's own
 * body. Before this fix, 15 of 15 module {@code unregisterSelf()} overrides never reached {@link
 * CommandManager#unregisterAll(UltiToolsPlugin)} at all, and 9 of 11 {@code reloadSelf()}
 * overrides never reached the framework's own reload steps -- measured in {@code 16-INTAKE.md} --
 * because both methods were plain non-final override points, not template methods.
 * <p>
 * Follows the mock-and-reflect idiom proven by {@code UltiToolsPluginLanguageFallbackTest} and
 * {@code ConditionalRegistrationEvaluatorDriftTest}: {@code mock(FixturePlugin.class)} bypasses
 * the constructor (Objenesis, via Mockito's inline mock maker), {@code doCallRealMethod()} runs
 * the REAL {@code unregisterSelf()}/{@code reloadSelf()} body under test, and {@link
 * TestHelper#mockUltiToolsInstance} substitutes mock managers for the static {@code
 * UltiTools.getInstance()} delegation those bodies call through.
 *
 * @since 6.3.0
 */
@DisplayName("UltiToolsPlugin lifecycle hook contract (D-01/D-02/D-03, #419/#455)")
class UltiToolsPluginLifecycleHookTest {

    private CommandManager mockCommandManager;
    private ListenerManager mockListenerManager;
    private ConfigManager mockConfigManager;

    /** Bare fixture: overrides neither hook. */
    abstract static class FixturePlugin extends UltiToolsPlugin {
    }

    /** Overrides {@code onUnregister()} with real, observable work. */
    abstract static class OverridingOnUnregisterFixturePlugin extends UltiToolsPlugin {
        boolean onUnregisterRan = false;

        @Override
        protected void onUnregister() {
            onUnregisterRan = true;
        }
    }

    /**
     * Overrides {@code onReload()}, snapshotting -- at the moment it runs -- whether the
     * framework's own config-reload step already ran and how many reload log records had
     * already been captured. Both snapshots must read "already happened" if the hook truly runs
     * last.
     */
    abstract static class OverridingOnReloadFixturePlugin extends UltiToolsPlugin {
        boolean onReloadRan = false;
        boolean configAlreadyReloadedWhenOnReloadRan = false;
        int capturedLogCountWhenOnReloadRan = -1;
        AtomicBoolean configReloadedFlag;
        List<LogRecord> capturedLogs;

        @Override
        protected void onReload() {
            onReloadRan = true;
            configAlreadyReloadedWhenOnReloadRan = configReloadedFlag != null && configReloadedFlag.get();
            capturedLogCountWhenOnReloadRan = capturedLogs != null ? capturedLogs.size() : -1;
        }
    }

    // Log capture for the D-03 per-module reload line, mirroring
    // ConditionalRegistrationEvaluatorDriftTest's proven Handler-capture idiom.
    private final List<LogRecord> capturedLogs = new ArrayList<>();
    private Handler captureHandler;

    @BeforeEach
    void setUp() {
        mockCommandManager = mock(CommandManager.class);
        mockListenerManager = mock(ListenerManager.class);
        mockConfigManager = mock(ConfigManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getCommandManager()).thenReturn(mockCommandManager);
            when(ultiTools.getListenerManager()).thenReturn(mockListenerManager);
            when(ultiTools.getConfigManager()).thenReturn(mockConfigManager);
        });

        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                capturedLogs.add(record);
            }

            @Override
            public void flush() {
                // Captured records are appended straight to the in-memory list; nothing to flush.
            }

            @Override
            public void close() {
                // Captured records outlive this handler on purpose (read after tearDown detaches it).
            }
        };
        Logger.getLogger(UltiToolsPlugin.class.getName()).addHandler(captureHandler);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger(UltiToolsPlugin.class.getName()).removeHandler(captureHandler);
    }

    /**
     * A bare {@code mock(fixtureClass)} wired for a safe {@code reloadSelf()} call: the private
     * {@code resourceFolderPath} field is set (via reflection, mirroring
     * {@code UltiToolsPluginLanguageFallbackTest}/{@code ConditionalRegistrationEvaluatorDriftTest})
     * to a directory with no {@code lang/} files, and {@code getLogger()} is stubbed to a mock
     * {@code PluginLogger} so the "no loadable language file" warning path cannot NPE against an
     * unstubbed {@code UltiTools.getInstance().getLogger()}.
     */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // mirrors the proven idiom from
    // UltiToolsPluginLanguageFallbackTest / ConditionalRegistrationEvaluatorDriftTest
    private <T extends UltiToolsPlugin> T reloadSafePlugin(Class<T> fixtureClass, String pluginName) throws Exception {
        T plugin = mock(fixtureClass);
        when(plugin.getPluginName()).thenReturn(pluginName);
        when(plugin.getLogger()).thenReturn(mock(PluginLogger.class));
        Field resourceFolderPathField = UltiToolsPlugin.class.getDeclaredField("resourceFolderPath");
        resourceFolderPathField.setAccessible(true);
        resourceFolderPathField.set(plugin, System.getProperty("java.io.tmpdir"));
        return plugin;
    }

    /** Reads a classpath resource (the packaged {@code lang/*.json} catalogues) as UTF-8 text. */
    private static String readClasspathResource(String resourcePath) throws IOException {
        try (InputStream in = UltiToolsPlugin.class.getResourceAsStream(resourcePath)) {
            assertThat(in).as("classpath resource must exist: " + resourcePath).isNotNull();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    sb.append(buffer, 0, read);
                }
                return sb.toString();
            }
        }
    }

    private static Map<String, String> parseJsonMap(String json) {
        return new Gson().fromJson(json, new TypeToken<Map<String, String>>() {
        }.getType());
    }

    // ==================== Task 1: unregisterSelf() / onUnregister() (D-01/D-02) ====================

    @Test
    @DisplayName("unregisterSelf() is declared final -- deleting the keyword must turn this test red")
    void unregisterSelfIsDeclaredFinal() throws NoSuchMethodException {
        Method method = UltiToolsPlugin.class.getDeclaredMethod("unregisterSelf");
        assertTrue(Modifier.isFinal(method.getModifiers()),
                "UltiToolsPlugin.unregisterSelf() must be final so a module cannot override it "
                        + "and skip the framework's own command/listener unregistration (D-01)");
    }

    @Test
    @DisplayName("a plugin that does not override onUnregister() still has its commands unregistered exactly once")
    void defaultOnUnregisterStillUnregistersCommandsOnce() {
        UltiToolsPlugin plugin = mock(FixturePlugin.class);
        doCallRealMethod().when(plugin).unregisterSelf();

        plugin.unregisterSelf();

        verify(mockCommandManager, times(1)).unregisterAll(plugin);
    }

    @Test
    @DisplayName("a plugin overriding onUnregister() cannot suppress the framework's own command unregistration")
    void overridingOnUnregisterDoesNotSuppressFrameworkUnregistration() {
        OverridingOnUnregisterFixturePlugin plugin = mock(OverridingOnUnregisterFixturePlugin.class);
        doCallRealMethod().when(plugin).unregisterSelf();
        doCallRealMethod().when(plugin).onUnregister();

        plugin.unregisterSelf();

        assertTrue(plugin.onUnregisterRan, "the module's onUnregister() override must have run");
        verify(mockCommandManager, times(1)).unregisterAll(plugin);
    }

    @Test
    @DisplayName("onUnregister() completes before CommandManager.unregisterAll and ListenerManager.unregisterAll, in that order")
    void onUnregisterRunsBeforeFrameworkUnregistersCommandsAndListeners() {
        UltiToolsPlugin plugin = mock(FixturePlugin.class);
        doCallRealMethod().when(plugin).unregisterSelf();

        plugin.unregisterSelf();

        InOrder order = inOrder(plugin, mockCommandManager, mockListenerManager);
        order.verify(plugin).onUnregister();
        order.verify(mockCommandManager).unregisterAll(plugin);
        order.verify(mockListenerManager).unregisterAll(plugin);
    }

    // ==================== Task 2: reloadSelf() / onReload() (D-02/D-03) ====================

    @Test
    @DisplayName("reloadSelf() is declared final -- deleting the keyword must turn this test red")
    void reloadSelfIsDeclaredFinal() throws NoSuchMethodException {
        Method method = UltiToolsPlugin.class.getDeclaredMethod("reloadSelf");
        assertTrue(Modifier.isFinal(method.getModifiers()),
                "UltiToolsPlugin.reloadSelf() must be final so a module cannot override it and "
                        + "skip the framework's own config-reload / language-refresh / drift-report "
                        + "steps or the framework-owned per-module reload log line (D-01)");
    }

    @Test
    @DisplayName("onReload() runs after the framework's config reload and after the reload log line, in that order")
    void onReloadRunsAfterConfigReloadAndReloadLogLine() throws Exception {
        OverridingOnReloadFixturePlugin plugin = reloadSafePlugin(OverridingOnReloadFixturePlugin.class, "TestModule");
        AtomicBoolean configReloadedFlag = new AtomicBoolean(false);
        plugin.configReloadedFlag = configReloadedFlag;
        plugin.capturedLogs = capturedLogs;
        doAnswer(invocation -> {
            configReloadedFlag.set(true);
            return null;
        }).when(mockConfigManager).reloadConfigs(any());
        doCallRealMethod().when(plugin).reloadSelf();
        doCallRealMethod().when(plugin).onReload();

        plugin.reloadSelf();

        assertTrue(plugin.onReloadRan, "the module's onReload() override must have run");
        assertTrue(plugin.configAlreadyReloadedWhenOnReloadRan,
                "ConfigManager.reloadConfigs() must have already run by the time onReload() runs");
        assertThat(plugin.capturedLogCountWhenOnReloadRan)
                .as("the per-module reload log line must already have been emitted by the time "
                        + "onReload() runs -- a count of 0 here means onReload() ran before the "
                        + "log line, or the log line was never emitted at all")
                .isEqualTo(1);

        InOrder order = inOrder(mockConfigManager, plugin);
        order.verify(mockConfigManager).reloadConfigs(plugin);
        order.verify(plugin).onReload();
    }

    @Test
    @DisplayName("reloading a plugin emits exactly one INFO record naming that plugin, produced by the framework")
    void reloadEmitsExactlyOneInfoRecordNamingThePlugin() throws Exception {
        UltiToolsPlugin plugin = reloadSafePlugin(FixturePlugin.class, "MyModule");
        doCallRealMethod().when(plugin).reloadSelf();

        plugin.reloadSelf();

        List<LogRecord> infoRecords = new ArrayList<>();
        for (LogRecord record : capturedLogs) {
            if (Level.INFO.equals(record.getLevel())) {
                infoRecords.add(record);
            }
        }
        assertThat(infoRecords)
                .as("reloadSelf() must emit exactly one framework-owned INFO record naming the "
                        + "reloaded module; zero means the log line is missing, more than one means "
                        + "it fired more than once")
                .hasSize(1);
        assertThat(infoRecords.get(0).getMessage())
                .as("the record's rendered message must name the reloaded plugin, fully formatted "
                        + "(no raw '%s' placeholder left unsubstituted)")
                .contains("MyModule")
                .doesNotContain("%s");
    }

    @Test
    @DisplayName("reloading two different plugins each emits its own record naming only that plugin")
    void reloadingTwoDifferentPluginsEachEmitsItsOwnRecord() throws Exception {
        UltiToolsPlugin pluginA = reloadSafePlugin(FixturePlugin.class, "PluginA");
        UltiToolsPlugin pluginB = reloadSafePlugin(FixturePlugin.class, "PluginB");
        doCallRealMethod().when(pluginA).reloadSelf();
        doCallRealMethod().when(pluginB).reloadSelf();

        pluginA.reloadSelf();
        pluginB.reloadSelf();

        assertThat(capturedLogs)
                .as("reloading two plugins must produce exactly two records total, one each")
                .hasSize(2);
        assertThat(capturedLogs.get(0).getMessage()).contains("PluginA").doesNotContain("PluginB");
        assertThat(capturedLogs.get(1).getMessage()).contains("PluginB").doesNotContain("PluginA");
    }

    @Test
    @DisplayName("the reload log message key resolves through both shipped language catalogues, not a raw key leak")
    void reloadLogMessageKeyResolvesThroughBothShippedCatalogues() throws IOException {
        Map<String, String> enCatalogue = parseJsonMap(readClasspathResource("/lang/en.json"));
        Map<String, String> zhCatalogue = parseJsonMap(readClasspathResource("/lang/zh.json"));

        assertThat(enCatalogue)
                .as("lang/en.json must carry an entry for the framework's reload-log message key")
                .containsKey(UltiToolsPlugin.RELOAD_LOG_MESSAGE_KEY);
        assertThat(zhCatalogue)
                .as("lang/zh.json must carry an entry for the framework's reload-log message key")
                .containsKey(UltiToolsPlugin.RELOAD_LOG_MESSAGE_KEY);
        assertThat(zhCatalogue.get(UltiToolsPlugin.RELOAD_LOG_MESSAGE_KEY))
                .as("zh.json's value must be a real Chinese-language translation, not merely "
                        + "identical to the (English) key -- equal values here would mean a "
                        + "Chinese-locale server sees the raw English key text leak through")
                .isNotEqualTo(UltiToolsPlugin.RELOAD_LOG_MESSAGE_KEY);
    }
}
