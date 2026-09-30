package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.Answers;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.DataStore;

/**
 * #528: a superseded copy whose unload hook throws must not take the incoming copy down with it.
 * <p>
 * Before the fix the older copy's failure escaped {@code unregisterSupersededVersions} into the
 * incoming copy's registration handler, which logged "&lt;name&gt; load failed" against the
 * incoming version -- which had not failed -- and returned {@code false}: the new version was
 * never activated, and the old one, its commands and listeners already torn down, stayed listed.
 */
@DisplayName("A superseded copy's unload failure is reported against it and does not abort the incoming copy (#528)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginManagerSupersedeFailureTest {

    private static final int CURRENT_API_VERSION = 625;

    private PluginManager pluginManager;
    private MockedStatic<UltiTools> ultiToolsStatic;
    private final List<LogRecord> bukkitLogs = new ArrayList<>();
    private Handler captureHandler;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        UltiTools ultiTools = mock(UltiTools.class);
        DependenceManagers dependenceManagers = mock(DependenceManagers.class);
        lenient().when(dependenceManagers.getContext()).thenReturn(new SimpleContainer());
        lenient().when(ultiTools.getDependenceManagers()).thenReturn(dependenceManagers);
        lenient().when(ultiTools.getLogger()).thenReturn(mock(Logger.class));
        lenient().when(ultiTools.getDataStore()).thenReturn(mock(DataStore.class, Answers.CALLS_REAL_METHODS));
        lenient().when(ultiTools.getConfigManager()).thenReturn(mock(ConfigManager.class));
        ultiToolsStatic = mockStatic(UltiTools.class);
        ultiToolsStatic.when(UltiTools::getInstance).thenReturn(ultiTools);
        ultiToolsStatic.when(UltiTools::getPluginVersion).thenReturn(CURRENT_API_VERSION);
        pluginManager = new PluginManager();

        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                bukkitLogs.add(record);
            }

            @Override
            public void flush() {
                // records are appended straight to the list
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
        if (ultiToolsStatic != null) {
            ultiToolsStatic.close();
        }
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    private static UltiToolsPlugin module(String version) {
        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("Dup");
        lenient().when(plugin.getMainClass()).thenReturn("com.example.Dup");
        lenient().when(plugin.getVersion()).thenReturn(version);
        lenient().when(plugin.getMinUltiToolsVersion()).thenReturn(CURRENT_API_VERSION);
        return plugin;
    }

    private List<String> messagesAtOrAbove(Level level) {
        List<String> messages = new ArrayList<>();
        for (LogRecord record : bukkitLogs) {
            if (record.getLevel().intValue() >= level.intValue() && record.getMessage() != null) {
                messages.add(record.getMessage());
            }
        }
        return messages;
    }

    @Test
    @DisplayName("the incoming copy is listed and active, the old one is gone, and the failure names the superseded version")
    void throwingSupersededUnloadDoesNotAbortTheIncomingCopy() throws Exception {
        UltiToolsPlugin older = module("1.0.0");
        SimpleContainer olderContext = mock(SimpleContainer.class);
        when(older.getContext()).thenReturn(olderContext);
        doThrow(new IllegalStateException("old copy's onUnregister failed")).when(older).unregisterSelf();
        UltiToolsPlugin newer = module("2.0.0");
        when(newer.isNewerVersionThan(older)).thenReturn(true);
        when(newer.registerSelf()).thenReturn(true);
        PluginListSeeding.add(pluginManager, older);

        boolean registered = pluginManager.register(newer);

        assertThat(registered).as("the outgoing copy's cleanup failure must not abort the incoming copy").isTrue();
        assertThat(pluginManager.getPluginList()).containsExactly(newer);
        verify(olderContext).close();
        List<String> problems = messagesAtOrAbove(Level.WARNING);
        assertThat(problems)
                .as("the failure is reported against the superseded copy by name and version")
                .anySatisfy(message -> assertThat(message).contains("Dup").contains("1.0.0")
                        .contains("old copy's onUnregister failed"));
        assertThat(problems)
                .as("the incoming version did not fail to load and must not be reported as failing")
                .noneSatisfy(message -> assertThat(message).contains("load failed"));
    }

    @Test
    @DisplayName("a fatal virtual-machine error from the superseded unload is not swallowed as an ordinary unload failure")
    void fatalErrorFromTheSupersededUnloadIsNotTreatedAsRecoverable() throws Exception {
        UltiToolsPlugin older = module("1.0.0");
        doThrow(new OutOfMemoryError("simulated")).when(older).unregisterSelf();
        UltiToolsPlugin newer = module("2.0.0");
        when(newer.isNewerVersionThan(older)).thenReturn(true);
        when(newer.registerSelf()).thenReturn(true);
        PluginListSeeding.add(pluginManager, older);

        boolean registered = pluginManager.register(newer);

        assertThat(registered)
                .as("an OutOfMemoryError is not an old copy's cleanup failure to log and carry on from")
                .isFalse();
        assertThat(pluginManager.getPluginList()).doesNotContain(newer);
    }
}
