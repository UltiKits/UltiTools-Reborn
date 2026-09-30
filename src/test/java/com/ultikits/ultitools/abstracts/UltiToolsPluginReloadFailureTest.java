package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #509: {@link UltiToolsPlugin#reloadSelf()} logs a module as reloaded only after its reload hook
 * returned, and logs a failure line naming the module -- never a success line -- when the hook
 * throws. Before the fix the success line was logged first and the hook ran unguarded, so a failed
 * reload printed "Module 'X' reloaded." followed by a stack trace.
 */
@DisplayName("reloadSelf() reports a failed reload as a failure, never as a success (#509)")
class UltiToolsPluginReloadFailureTest {

    private final List<LogRecord> capturedLogs = new ArrayList<>();
    private Handler captureHandler;

    /** Bare fixture: the hook is stubbed per test. */
    abstract static class FixturePlugin extends UltiToolsPlugin {
    }

    @BeforeEach
    void setUp() {
        ConfigManager configManager = mock(ConfigManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getCommandManager()).thenReturn(mock(CommandManager.class));
            when(ultiTools.getListenerManager()).thenReturn(mock(ListenerManager.class));
            when(ultiTools.getConfigManager()).thenReturn(configManager);
        });
        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                capturedLogs.add(record);
            }

            @Override
            public void flush() {
                // records go straight to the list
            }

            @Override
            public void close() {
                // records outlive the handler on purpose
            }
        };
        Logger.getLogger(UltiToolsPlugin.class.getName()).addHandler(captureHandler);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger(UltiToolsPlugin.class.getName()).removeHandler(captureHandler);
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // same idiom as UltiToolsPluginLifecycleHookTest
    private FixturePlugin reloadSafePlugin(String name) throws Exception {
        FixturePlugin plugin = mock(FixturePlugin.class);
        when(plugin.getPluginName()).thenReturn(name);
        when(plugin.getLogger()).thenReturn(mock(PluginLogger.class));
        Field resourceFolderPathField = UltiToolsPlugin.class.getDeclaredField("resourceFolderPath");
        resourceFolderPathField.setAccessible(true);
        resourceFolderPathField.set(plugin, System.getProperty("java.io.tmpdir"));
        doCallRealMethod().when(plugin).reloadSelf();
        return plugin;
    }

    private List<LogRecord> recordsAt(Level level) {
        List<LogRecord> result = new ArrayList<>();
        for (LogRecord record : capturedLogs) {
            if (level.equals(record.getLevel())) {
                result.add(record);
            }
        }
        return result;
    }

    private static Map<String, String> catalogue(String resource) throws IOException {
        try (InputStream in = UltiToolsPlugin.class.getResourceAsStream(resource);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, new TypeToken<Map<String, String>>() {
            }.getType());
        }
    }

    @Test
    @DisplayName("a throwing reload hook gets a failure line naming the module, no success line, and the failure propagates")
    void throwingHookIsLoggedAsAFailureAndPropagates() throws Exception {
        FixturePlugin plugin = reloadSafePlugin("BrokenModule");
        IllegalStateException hookFailure = new IllegalStateException("service restart failed");
        doThrow(hookFailure).when(plugin).onReload();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, plugin::reloadSelf);

        assertThat(thrown).isSameAs(hookFailure);
        assertThat(recordsAt(Level.INFO))
                .as("no success line may be logged for a reload whose hook threw")
                .isEmpty();
        assertThat(recordsAt(Level.SEVERE))
                .as("exactly one failure line, naming the module and carrying the failure")
                .hasSize(1)
                .allSatisfy(record -> {
                    assertThat(record.getMessage()).contains("BrokenModule").contains("service restart failed")
                            .doesNotContain("%s");
                    assertThat(record.getThrown()).isSameAs(hookFailure);
                });
    }

    @Test
    @DisplayName("the success line is logged after the hook returned")
    void successLineComesAfterTheHook() throws Exception {
        FixturePlugin plugin = reloadSafePlugin("GoodModule");
        List<Integer> recordsWhenHookRan = new ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            recordsWhenHookRan.add(capturedLogs.size());
            return null;
        }).when(plugin).onReload();

        plugin.reloadSelf();

        assertThat(recordsWhenHookRan).as("the hook ran once, before any reload line was logged").containsExactly(0);
        assertThat(recordsAt(Level.INFO)).hasSize(1)
                .allSatisfy(record -> assertThat(record.getMessage()).contains("GoodModule"));
    }

    @Test
    @DisplayName("the failure line resolves through both shipped catalogues")
    void failureLineIsTranslatedInBothCatalogues() throws IOException {
        Map<String, String> en = catalogue("/lang/en.json");
        Map<String, String> zh = catalogue("/lang/zh.json");

        assertThat(en).containsKey(UltiToolsPlugin.RELOAD_FAILED_LOG_MESSAGE_KEY);
        assertThat(zh).containsKey(UltiToolsPlugin.RELOAD_FAILED_LOG_MESSAGE_KEY);
        assertThat(zh.get(UltiToolsPlugin.RELOAD_FAILED_LOG_MESSAGE_KEY))
                .isNotEqualTo(UltiToolsPlugin.RELOAD_FAILED_LOG_MESSAGE_KEY);
    }
}
