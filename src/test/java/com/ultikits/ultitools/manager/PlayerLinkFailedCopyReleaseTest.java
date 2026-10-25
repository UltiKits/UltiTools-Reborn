package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.plugin.PluginDescriptionFile;
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
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.utils.CloudFeatures;
import com.ultikits.ultitools.websocket.CloudPlayerLinks;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;

/**
 * Plan 22-21 fix round, gap 3 (surviving mutant M3): a newer copy of a module that sets the player-link handler while
 * it loads and then fails to load gives the handler up again, through the real {@link PluginManager#register} path
 * (#562 handover restore), so a completion never reaches the failed copy's class loader and another copy can set the
 * handler afterwards.
 */
@DisplayName("A failed newer copy releases the player-link handler it set")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PlayerLinkFailedCopyReleaseTest {

    private static final int CURRENT_API_VERSION = 625;
    private static final CloudPlayerLinks.LinkEventHandler HANDLER =
            completion -> CompletableFuture.completedFuture(CloudPlayerLinks.GameAccountResult.CREATED);

    private PluginManager pluginManager;
    private EventBus eventBus;
    private PanelResponderRegistry responders;
    private MockedStatic<UltiTools> ultiToolsStatic;
    private CloudFeatures features;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        eventBus = new EventBus();
        responders = new PanelResponderRegistry();
        UltiTools ultiTools = mock(UltiTools.class);
        DependenceManagers dependenceManagers = mock(DependenceManagers.class);
        lenient().when(dependenceManagers.getContext()).thenReturn(new SimpleContainer());
        lenient().when(ultiTools.getDependenceManagers()).thenReturn(dependenceManagers);
        lenient().when(ultiTools.getLogger()).thenReturn(mock(Logger.class));
        lenient().when(ultiTools.getDataStore()).thenReturn(mock(DataStore.class, Answers.CALLS_REAL_METHODS));
        lenient().when(ultiTools.getConfigManager()).thenReturn(mock(ConfigManager.class));
        lenient().when(ultiTools.getEventBus()).thenReturn(eventBus);
        lenient().when(ultiTools.getPanelResponderRegistry()).thenReturn(responders);
        lenient().when(ultiTools.getDescription()).thenReturn(
                new PluginDescriptionFile("UltiTools", "6.3.1", "com.ultikits.ultitools.UltiTools"));
        features = CloudFeatures.start(ultiTools);
        lenient().when(ultiTools.getCloudPlayerLinks()).thenAnswer(invocation -> features.playerLinks());
        ultiToolsStatic = mockStatic(UltiTools.class);
        ultiToolsStatic.when(UltiTools::getInstance).thenReturn(ultiTools);
        ultiToolsStatic.when(UltiTools::getPluginVersion).thenReturn(CURRENT_API_VERSION);
        pluginManager = new PluginManager();
        lenient().when(ultiTools.getPluginManager()).thenReturn(pluginManager);
    }

    @AfterEach
    void tearDown() {
        features.shutdown();
        eventBus.shutdown();
        responders.shutdown();
        if (ultiToolsStatic != null) {
            ultiToolsStatic.close();
        }
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    private static UltiToolsPlugin module(String version) {
        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("UltiLogin");
        lenient().when(plugin.getMainClass()).thenReturn("com.example.UltiLogin");
        lenient().when(plugin.getVersion()).thenReturn(version);
        lenient().when(plugin.getMinUltiToolsVersion()).thenReturn(CURRENT_API_VERSION);
        return plugin;
    }

    @Test
    @DisplayName("registerSelf() sets the handler, returns false: the handler is free again afterwards")
    void failedCopyReleasesTheHandler() throws Exception {
        UltiToolsPlugin older = module("1.0.0");
        when(older.registerSelf()).thenReturn(true);
        assertThat(pluginManager.register(older)).isTrue();
        final UltiToolsPlugin newer = module("2.0.0");
        when(newer.isNewerVersionThan(older)).thenReturn(true);
        when(newer.registerSelf()).thenAnswer(invocation -> {
            features.playerLinks().setHandler(newer, HANDLER);
            return false;
        });

        assertThat(pluginManager.register(newer)).as("the newer copy does not load").isFalse();

        assertThatCode(() -> features.playerLinks().setHandler(older, HANDLER))
                .as("the failed copy no longer holds the handler").doesNotThrowAnyException();
    }
}
