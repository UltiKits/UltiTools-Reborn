package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Pins the combination of two changes to one statement, made by two pull requests: the
 * server-thread guard on {@code reloadSelf()} (#538, configuration refactor) and the split of
 * {@code reloadSelf()} into a shared body behind {@code reloadSelf()} and
 * {@code reloadWithReport()} (#529, lifecycle). Off the server thread both entry points are
 * refused with the guard's single warning and run nothing; on the server thread both run the full
 * reload and the hook's report comes back.
 */
@DisplayName("Both reload entry points keep the server-thread guard and the reload report")
class ReloadEntryPointsServerThreadGuardTest {

    private final List<LogRecord> pluginClassLogs = new ArrayList<>();
    private Handler captureHandler;
    private ConfigManager configManager;

    /** Records that its hook ran and reports one part it could not reload. */
    abstract static class GuardFixturePlugin extends UltiToolsPlugin {
        boolean hookRan;

        @Override
        protected void onReload(ReloadReport report) {
            hookRan = true;
            report.partial("fixture part did not reload");
        }
    }

    @BeforeEach
    void setUp() {
        configManager = mock(ConfigManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> when(ultiTools.getConfigManager()).thenReturn(configManager));
        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                pluginClassLogs.add(record);
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

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // same idiom as UltiToolsPluginPartialReloadTest
    private GuardFixturePlugin fixture() throws Exception {
        GuardFixturePlugin plugin = mock(GuardFixturePlugin.class);
        when(plugin.getPluginName()).thenReturn("GuardedReloadModule");
        when(plugin.getLogger()).thenReturn(mock(PluginLogger.class));
        when(plugin.getConfigManager()).thenReturn(configManager);
        Field resourceFolderPathField = UltiToolsPlugin.class.getDeclaredField("resourceFolderPath");
        resourceFolderPathField.setAccessible(true);
        resourceFolderPathField.set(plugin, System.getProperty("java.io.tmpdir"));
        doCallRealMethod().when(plugin).reloadSelf();
        doCallRealMethod().when(plugin).reloadWithReport();
        doCallRealMethod().when(plugin).onReload(any(ReloadReport.class));
        return plugin;
    }

    @Test
    @DisplayName("off the server thread, reloadSelf() is refused with one warning and runs nothing")
    void reloadSelfOffThreadIsRefused() throws Exception {
        GuardFixturePlugin plugin = fixture();
        Logger bukkitLogger = mock(Logger.class);
        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
            when(Bukkit.getServer()).thenReturn(mock(Server.class));
            when(Bukkit.isPrimaryThread()).thenReturn(false);
            when(Bukkit.getLogger()).thenReturn(bukkitLogger);

            plugin.reloadSelf();
        }
        assertRefused(plugin, bukkitLogger);
    }

    @Test
    @DisplayName("off the server thread, reloadWithReport() is refused the same way and its report names the refusal")
    void reloadWithReportOffThreadIsRefused() throws Exception {
        GuardFixturePlugin plugin = fixture();
        Logger bukkitLogger = mock(Logger.class);
        ReloadReport report;
        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
            when(Bukkit.getServer()).thenReturn(mock(Server.class));
            when(Bukkit.isPrimaryThread()).thenReturn(false);
            when(Bukkit.getLogger()).thenReturn(bukkitLogger);

            report = plugin.reloadWithReport();
        }
        assertRefused(plugin, bukkitLogger);
        assertThat(report.getPartialReasons()).containsExactly(UltiToolsPlugin.RELOAD_REFUSED_OFF_THREAD_REASON);
    }

    @Test
    @DisplayName("on the server thread, reloadWithReport() runs the reload and returns the hook's report")
    void reloadWithReportOnServerThreadRuns() throws Exception {
        GuardFixturePlugin plugin = fixture();
        ReloadReport report;
        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
            when(Bukkit.getServer()).thenReturn(mock(Server.class));
            when(Bukkit.isPrimaryThread()).thenReturn(true);
            when(Bukkit.getLogger()).thenReturn(mock(Logger.class));

            report = plugin.reloadWithReport();
        }
        assertThat(plugin.hookRan).isTrue();
        verify(configManager, times(1)).reloadConfigs(plugin);
        assertThat(report.getPartialReasons()).containsExactly("fixture part did not reload");
    }

    @Test
    @DisplayName("on the server thread, reloadSelf() runs the same reload")
    void reloadSelfOnServerThreadRuns() throws Exception {
        GuardFixturePlugin plugin = fixture();
        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
            when(Bukkit.getServer()).thenReturn(mock(Server.class));
            when(Bukkit.isPrimaryThread()).thenReturn(true);
            when(Bukkit.getLogger()).thenReturn(mock(Logger.class));

            plugin.reloadSelf();
        }
        assertThat(plugin.hookRan).isTrue();
        verify(configManager, times(1)).reloadConfigs(plugin);
    }

    private void assertRefused(GuardFixturePlugin plugin, Logger bukkitLogger) {
        assertThat(plugin.hookRan).as("the module's hook must not run").isFalse();
        verify(configManager, never()).reloadConfigs(any());
        ArgumentCaptor<String> warning = ArgumentCaptor.forClass(String.class);
        verify(bukkitLogger, times(1)).log(Mockito.eq(Level.WARNING), warning.capture());
        assertThat(warning.getValue()).contains("GuardedReloadModule", Thread.currentThread().getName());
        assertThat(pluginClassLogs).as("no reloaded, partial or failed line for a refused reload").isEmpty();
    }
}
