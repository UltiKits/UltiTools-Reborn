package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #509: {@link PluginManager#reload()} isolates each module's reload, as {@link PluginManager#close()}
 * isolates each module's unload. Before the fix it looped with no guard, so the first module whose
 * reload threw stopped every module after it, and "All plugins reloaded." was never logged -- nor
 * replaced by anything saying which module failed.
 */
@DisplayName("PluginManager.reload() isolates a failing module and names it in the summary (#509)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginManagerReloadIsolationTest {

    private PluginManager pluginManager;
    private final List<LogRecord> bukkitLogs = new ArrayList<>();
    private Handler captureHandler;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        TestHelper.mockUltiToolsInstance();
        pluginManager = new PluginManager();
        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                bukkitLogs.add(record);
            }

            @Override
            public void flush() {
                // records go straight to the list
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        Bukkit.getLogger().addHandler(captureHandler);
    }

    @AfterEach
    void tearDown() {
        Bukkit.getLogger().removeHandler(captureHandler);
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    private static UltiToolsPlugin module(String name) {
        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        when(plugin.getPluginName()).thenReturn(name);
        return plugin;
    }

    private List<String> loggedMessages() {
        List<String> messages = new ArrayList<>();
        for (LogRecord record : bukkitLogs) {
            messages.add(String.valueOf(record.getMessage()));
        }
        return messages;
    }

    @Test
    @DisplayName("a throwing module in the middle does not stop the next one, and the summary names it")
    void throwingModuleInTheMiddleIsIsolatedAndNamed() {
        UltiToolsPlugin first = module("FirstModule");
        UltiToolsPlugin broken = module("BrokenModule");
        UltiToolsPlugin last = module("LastModule");
        doThrow(new IllegalStateException("hook boom")).when(broken).reloadSelf();
        PluginListSeeding.add(pluginManager, first);
        PluginListSeeding.add(pluginManager, broken);
        PluginListSeeding.add(pluginManager, last);

        assertDoesNotThrow(pluginManager::reload, "one module's failure must not escape /ul reload");

        verify(first).reloadSelf();
        verify(last).reloadSelf();
        assertThat(loggedMessages())
                .as("the summary names the failed module")
                .anySatisfy(message -> assertThat(message).contains("BrokenModule").doesNotContain("%s"))
                .as("an unconditional success line must not be logged when a module failed")
                .noneSatisfy(message -> assertThat(message).contains("All plugins reloaded"));
    }

    @Test
    @DisplayName("the reported summary names the failed module and counts the others")
    void reportedSummaryNamesTheFailedModule() {
        UltiToolsPlugin good = module("GoodModule");
        UltiToolsPlugin broken = module("BrokenModule");
        doThrow(new IllegalStateException("hook boom")).when(broken).reloadSelf();
        PluginListSeeding.add(pluginManager, good);
        PluginListSeeding.add(pluginManager, broken);

        List<String> summary = pluginManager.reloadAllAndReport();

        assertThat(summary).isNotEmpty();
        assertThat(String.join("\n", summary)).contains("BrokenModule").doesNotContain("GoodModule")
                .contains("1").contains("2").doesNotContain("%");
    }

    @Test
    @DisplayName("with every module reloaded, the summary says so and names no module")
    void allReloadedSummary() {
        UltiToolsPlugin good = module("GoodModule");
        PluginListSeeding.add(pluginManager, good);

        List<String> summary = pluginManager.reloadAllAndReport();

        assertThat(summary).hasSize(1);
        assertThat(summary.get(0)).contains("1").doesNotContain("GoodModule").doesNotContain("%");
    }

    @Test
    @DisplayName("a fatal virtual-machine error is not swallowed as one module's reload failure")
    void fatalErrorPropagates() {
        UltiToolsPlugin broken = module("BrokenModule");
        UltiToolsPlugin last = module("LastModule");
        doThrow(new OutOfMemoryError("simulated")).when(broken).reloadSelf();
        PluginListSeeding.add(pluginManager, broken);
        PluginListSeeding.add(pluginManager, last);

        assertThrows(OutOfMemoryError.class, pluginManager::reload);
        verify(last, never()).reloadSelf();
    }
}
