package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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

import com.ultikits.ultitools.abstracts.ReloadReport;
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
        // #529: PluginManager reads each module's report
        when(plugin.reloadWithReport()).thenReturn(new ReloadReport());
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
        doThrow(new IllegalStateException("hook boom")).when(broken).reloadWithReport();
        PluginListSeeding.add(pluginManager, first);
        PluginListSeeding.add(pluginManager, broken);
        PluginListSeeding.add(pluginManager, last);

        assertDoesNotThrow(pluginManager::reload, "one module's failure must not escape /ul reload");

        verify(first).reloadWithReport();
        verify(last).reloadWithReport();
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
        doThrow(new IllegalStateException("hook boom")).when(broken).reloadWithReport();
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
    @DisplayName("#529: a partially reloaded module is listed with the parts that did not reload")
    void partialModuleIsListedWithItsReasons() {
        UltiToolsPlugin good = module("GoodModule");
        UltiToolsPlugin partial = module("PartialModule");
        ReloadReport report = new ReloadReport();
        report.partial("scoreboard service did not restart");
        when(partial.reloadWithReport()).thenReturn(report);
        PluginListSeeding.add(pluginManager, good);
        PluginListSeeding.add(pluginManager, partial);

        List<String> summary = pluginManager.reloadAllAndReport();

        String joined = String.join("\n", summary);
        assertThat(joined).contains("PartialModule").contains("scoreboard service did not restart")
                .doesNotContain("GoodModule").doesNotContain("%");
        assertThat(loggedMessages())
                .anySatisfy(message -> assertThat(message).contains("PartialModule")
                        .contains("scoreboard service did not restart"));
    }

    @Test
    @DisplayName("with one module failed and another partial, no summary line claims the others fully reloaded")
    void mixedFailureAndPartialSummaryDoesNotClaimTheOthersReloaded() {
        // Codex review of #564, round 2 (P2): the failure headline said "The others reloaded" while
        // a line below it listed a module that reloaded only partly.
        UltiToolsPlugin broken = module("BrokenModule");
        UltiToolsPlugin partial = module("PartialModule");
        UltiToolsPlugin good = module("GoodModule");
        doThrow(new IllegalStateException("hook boom")).when(broken).reloadWithReport();
        ReloadReport report = new ReloadReport();
        report.partial("scoreboard service did not restart");
        when(partial.reloadWithReport()).thenReturn(report);
        PluginListSeeding.add(pluginManager, broken);
        PluginListSeeding.add(pluginManager, partial);
        PluginListSeeding.add(pluginManager, good);

        List<String> summary = pluginManager.reloadAllAndReport();

        assertThat(summary).as("failure headline, partial count, one line for the partial module").hasSize(3);
        assertThat(summary)
                .noneSatisfy(line -> assertThat(line).containsIgnoringCase("the others reloaded"))
                .anySatisfy(line -> assertThat(line).contains("BrokenModule").contains("3"))
                .anySatisfy(line -> assertThat(line).contains("PartialModule")
                        .contains("scoreboard service did not restart"));
        assertThat(summary.get(1)).as("the mixed outcome counts the partial modules").contains("1").contains("partially");
    }

    @Test
    @DisplayName("an Error from one module's reload, such as a StackOverflowError, is isolated exactly as close() isolates one")
    void errorFromOneModuleIsIsolatedLikeClose() {
        // Gate-1 review (reviewer B IN-01): a recursive onReload() is the realistic module-code
        // Error; rethrowing it stopped every later module. The policy is now close()'s.
        UltiToolsPlugin broken = module("BrokenModule");
        UltiToolsPlugin last = module("LastModule");
        doThrow(new StackOverflowError("recursive onReload")).when(broken).reloadWithReport();
        PluginListSeeding.add(pluginManager, broken);
        PluginListSeeding.add(pluginManager, last);

        List<String> summary = assertDoesNotThrow(pluginManager::reloadAllAndReport);

        verify(last).reloadWithReport();
        assertThat(String.join("\n", summary)).contains("BrokenModule");
    }
}
