package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #529: a module's reload hook can report a partial reload through {@link ReloadReport}, and the
 * framework reports it instead of an unconditional success. {@link UltiToolsPlugin#reloadSelf()}
 * keeps its {@code public final void} signature; {@link UltiToolsPlugin#reloadWithReport()} runs
 * the same reload and returns the report.
 */
@DisplayName("A module can report a partial reload, and the framework says so (#529)")
class UltiToolsPluginPartialReloadTest {

    private final List<LogRecord> capturedLogs = new ArrayList<>();
    private Handler captureHandler;

    /** Records one part it could not reload. */
    abstract static class PartialFixturePlugin extends UltiToolsPlugin {
        @Override
        protected void onReload(ReloadReport report) {
            report.partial("scoreboard service did not restart");
        }
    }

    /** Overrides only the original hook, as every module compiled before 6.3.0 does. */
    abstract static class LegacyFixturePlugin extends UltiToolsPlugin {
        boolean onReloadRan;

        @Override
        protected void onReload() {
            onReloadRan = true;
        }
    }

    @BeforeEach
    void setUp() {
        ConfigManager configManager = mock(ConfigManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> when(ultiTools.getConfigManager()).thenReturn(configManager));
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
    private <T extends UltiToolsPlugin> T reloadSafePlugin(Class<T> type, String name) throws Exception {
        T plugin = mock(type);
        when(plugin.getPluginName()).thenReturn(name);
        when(plugin.getLogger()).thenReturn(mock(PluginLogger.class));
        Field resourceFolderPathField = UltiToolsPlugin.class.getDeclaredField("resourceFolderPath");
        resourceFolderPathField.setAccessible(true);
        resourceFolderPathField.set(plugin, System.getProperty("java.io.tmpdir"));
        doCallRealMethod().when(plugin).reloadSelf();
        doCallRealMethod().when(plugin).reloadWithReport();
        doCallRealMethod().when(plugin).onReload(any(ReloadReport.class));
        doCallRealMethod().when(plugin).onReload();
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

    @Test
    @DisplayName("a recorded partial reason comes back in the report and is logged instead of the success line")
    void partialReasonIsReturnedAndLogged() throws Exception {
        PartialFixturePlugin plugin = reloadSafePlugin(PartialFixturePlugin.class, "PartialModule");

        ReloadReport report = plugin.reloadWithReport();

        assertThat(report.getPartialReasons()).containsExactly("scoreboard service did not restart");
        assertThat(recordsAt(Level.INFO)).as("no plain success line for a partial reload").isEmpty();
        assertThat(recordsAt(Level.WARNING))
                .hasSize(1)
                .allSatisfy(record -> assertThat(record.getMessage()).contains("PartialModule")
                        .contains("scoreboard service did not restart").doesNotContain("%s"));
    }

    @Test
    @DisplayName("a module overriding only onReload() behaves exactly as before")
    void legacyOverrideBehavesAsBefore() throws Exception {
        LegacyFixturePlugin plugin = reloadSafePlugin(LegacyFixturePlugin.class, "LegacyModule");

        ReloadReport report = plugin.reloadWithReport();

        assertThat(plugin.onReloadRan).as("the default onReload(ReloadReport) calls onReload()").isTrue();
        assertThat(report.isPartial()).isFalse();
        assertThat(recordsAt(Level.INFO)).hasSize(1)
                .allSatisfy(record -> assertThat(record.getMessage()).contains("LegacyModule"));
        assertThat(recordsAt(Level.WARNING)).isEmpty();
    }

    @Test
    @DisplayName("reloadSelf() runs the same reload, including the new hook")
    void reloadSelfRunsTheNewHook() throws Exception {
        PartialFixturePlugin plugin = reloadSafePlugin(PartialFixturePlugin.class, "PartialModule");

        plugin.reloadSelf();

        verify(plugin).onReload(any(ReloadReport.class));
        assertThat(recordsAt(Level.WARNING)).hasSize(1);
    }

    @Test
    @DisplayName("reloadSelf() keeps its public final void signature; reloadWithReport() is public final")
    void signatures() throws NoSuchMethodException {
        Method reloadSelf = UltiToolsPlugin.class.getMethod("reloadSelf");
        assertThat(reloadSelf.getReturnType()).isEqualTo(void.class);
        assertThat(Modifier.isFinal(reloadSelf.getModifiers())).isTrue();

        Method reloadWithReport = UltiToolsPlugin.class.getMethod("reloadWithReport");
        assertThat(reloadWithReport.getReturnType()).isEqualTo(ReloadReport.class);
        assertThat(Modifier.isFinal(reloadWithReport.getModifiers())).isTrue();

        Method hook = UltiToolsPlugin.class.getDeclaredMethod("onReload", ReloadReport.class);
        assertThat(Modifier.isProtected(hook.getModifiers())).isTrue();
    }
}
