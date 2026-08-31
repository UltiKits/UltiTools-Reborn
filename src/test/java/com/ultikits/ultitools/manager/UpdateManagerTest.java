package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.logging.Logger;

import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.entities.UpdateInfo;
import com.ultikits.ultitools.utils.PluginInstallUtils;
import com.ultikits.ultitools.utils.VersionUtils;

@DisplayName("UpdateManager Tests")
class UpdateManagerTest {

    private UpdateManager updateManager;

    @BeforeEach
    void setUp() {
        updateManager = new UpdateManager(mock(Logger.class));
    }

    @Nested
    @DisplayName("checkUpdatesSync Tests")
    class CheckUpdatesSyncTests {

        @Test
        @DisplayName("Should detect framework update when newer version available")
        void shouldDetectFrameworkUpdate() {
            try (MockedStatic<VersionUtils> versionMock = mockStatic(VersionUtils.class);
                 MockedStatic<UltiTools> ultiMock = mockStatic(UltiTools.class)) {

                org.bukkit.configuration.file.YamlConfiguration env =
                    new org.bukkit.configuration.file.YamlConfiguration();
                env.set("version", "6.2.0");
                ultiMock.when(UltiTools::getEnv).thenReturn(env);

                UltiTools instance = mock(UltiTools.class);
                PluginManager pm = mock(PluginManager.class);
                when(pm.getPluginList()).thenReturn(Collections.emptyList());
                when(instance.getPluginManager()).thenReturn(pm);
                ultiMock.when(UltiTools::getInstance).thenReturn(instance);

                // Mock i18n to return the key itself
                when(instance.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

                versionMock.when(VersionUtils::getUltiToolsNewestVersion).thenReturn("6.3.0");

                updateManager.checkUpdatesSync();

                assertThat(updateManager.getFrameworkUpdate()).isNotNull();
                assertThat(updateManager.getFrameworkUpdate().getLatestVersion()).isEqualTo("6.3.0");
                assertThat(updateManager.getFrameworkUpdate().getCurrentVersion()).isEqualTo("6.2.0");
            }
        }

        @Test
        @DisplayName("Should not report framework update when already latest")
        void shouldNotReportWhenUpToDate() {
            try (MockedStatic<VersionUtils> versionMock = mockStatic(VersionUtils.class);
                 MockedStatic<UltiTools> ultiMock = mockStatic(UltiTools.class)) {

                org.bukkit.configuration.file.YamlConfiguration env =
                    new org.bukkit.configuration.file.YamlConfiguration();
                env.set("version", "6.3.0");
                ultiMock.when(UltiTools::getEnv).thenReturn(env);

                UltiTools instance = mock(UltiTools.class);
                PluginManager pm = mock(PluginManager.class);
                when(pm.getPluginList()).thenReturn(Collections.emptyList());
                when(instance.getPluginManager()).thenReturn(pm);
                ultiMock.when(UltiTools::getInstance).thenReturn(instance);
                when(instance.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

                versionMock.when(VersionUtils::getUltiToolsNewestVersion).thenReturn("6.3.0");

                updateManager.checkUpdatesSync();

                assertThat(updateManager.getFrameworkUpdate()).isNull();
            }
        }

        @Test
        @DisplayName("Should handle null newest version gracefully")
        void shouldHandleNullNewestVersion() {
            try (MockedStatic<VersionUtils> versionMock = mockStatic(VersionUtils.class);
                 MockedStatic<UltiTools> ultiMock = mockStatic(UltiTools.class)) {

                org.bukkit.configuration.file.YamlConfiguration env =
                    new org.bukkit.configuration.file.YamlConfiguration();
                env.set("version", "6.2.0");
                ultiMock.when(UltiTools::getEnv).thenReturn(env);

                UltiTools instance = mock(UltiTools.class);
                PluginManager pm = mock(PluginManager.class);
                when(pm.getPluginList()).thenReturn(Collections.emptyList());
                when(instance.getPluginManager()).thenReturn(pm);
                ultiMock.when(UltiTools::getInstance).thenReturn(instance);
                when(instance.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

                versionMock.when(VersionUtils::getUltiToolsNewestVersion).thenReturn(null);

                updateManager.checkUpdatesSync();

                assertThat(updateManager.getFrameworkUpdate()).isNull();
            }
        }

        @Test
        @DisplayName("Should detect module updates")
        void shouldDetectModuleUpdates() {
            try (MockedStatic<VersionUtils> versionMock = mockStatic(VersionUtils.class);
                 MockedStatic<PluginInstallUtils> installMock = mockStatic(PluginInstallUtils.class);
                 MockedStatic<UltiTools> ultiMock = mockStatic(UltiTools.class)) {

                org.bukkit.configuration.file.YamlConfiguration env =
                    new org.bukkit.configuration.file.YamlConfiguration();
                env.set("version", "6.2.0");
                ultiMock.when(UltiTools::getEnv).thenReturn(env);

                UltiToolsPlugin modulePlugin = mock(UltiToolsPlugin.class);
                when(modulePlugin.getPluginName()).thenReturn("UltiChat");
                when(modulePlugin.getIdentifyString()).thenReturn("ultichat");
                when(modulePlugin.getVersion()).thenReturn("1.0.0");

                UltiTools instance = mock(UltiTools.class);
                PluginManager pm = mock(PluginManager.class);
                when(pm.getPluginList()).thenReturn(Collections.singletonList(modulePlugin));
                when(instance.getPluginManager()).thenReturn(pm);
                ultiMock.when(UltiTools::getInstance).thenReturn(instance);
                when(instance.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

                versionMock.when(VersionUtils::getUltiToolsNewestVersion).thenReturn("6.2.0");
                installMock.when(() -> PluginInstallUtils.getPluginLatestVersion("ultichat"))
                    .thenReturn("1.1.0");

                updateManager.checkUpdatesSync();

                assertThat(updateManager.getModuleUpdates()).containsKey("UltiChat");
                UpdateInfo info = updateManager.getModuleUpdates().get("UltiChat");
                assertThat(info.getCurrentVersion()).isEqualTo("1.0.0");
                assertThat(info.getLatestVersion()).isEqualTo("1.1.0");
            }
        }

        @Test
        @DisplayName("Should skip modules without identifyString")
        void shouldSkipModulesWithoutIdentifyString() {
            try (MockedStatic<VersionUtils> versionMock = mockStatic(VersionUtils.class);
                 MockedStatic<UltiTools> ultiMock = mockStatic(UltiTools.class)) {

                org.bukkit.configuration.file.YamlConfiguration env =
                    new org.bukkit.configuration.file.YamlConfiguration();
                env.set("version", "6.2.0");
                ultiMock.when(UltiTools::getEnv).thenReturn(env);

                UltiToolsPlugin modulePlugin = mock(UltiToolsPlugin.class);
                when(modulePlugin.getPluginName()).thenReturn("LocalPlugin");
                when(modulePlugin.getIdentifyString()).thenReturn(null);
                when(modulePlugin.getVersion()).thenReturn("1.0.0");

                UltiTools instance = mock(UltiTools.class);
                PluginManager pm = mock(PluginManager.class);
                when(pm.getPluginList()).thenReturn(Collections.singletonList(modulePlugin));
                when(instance.getPluginManager()).thenReturn(pm);
                ultiMock.when(UltiTools::getInstance).thenReturn(instance);
                when(instance.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

                versionMock.when(VersionUtils::getUltiToolsNewestVersion).thenReturn("6.2.0");

                updateManager.checkUpdatesSync();

                assertThat(updateManager.getModuleUpdates()).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("hasAnyUpdates Tests")
    class HasAnyUpdatesTests {

        @Test
        @DisplayName("Should return false before check runs")
        void shouldReturnFalseBeforeCheck() {
            assertThat(updateManager.hasAnyUpdates()).isFalse();
        }
    }

    @Nested
    @DisplayName("Player Notification Tracking Tests")
    class NotificationTests {

        @Test
        @DisplayName("Should track notified players")
        void shouldTrackNotifiedPlayers() {
            UUID uuid = UUID.randomUUID();
            assertThat(updateManager.isPlayerNotified(uuid)).isFalse();

            updateManager.markPlayerNotified(uuid);

            assertThat(updateManager.isPlayerNotified(uuid)).isTrue();
        }
    }

    /**
     * GEN-08 / D-03: {@code notifiedPlayers} is now registered with the live {@link
     * PlayerCacheManager} (lazy first-use, triggered from {@link UpdateManager#markPlayerNotified}),
     * so a quitting player's UUID is pruned through the REAL quit path -- {@link
     * PlayerCacheManager#onPlayerQuit(UUID)} -- rather than never at all. At HEAD nothing ever
     * removes from this set (only {@code contains}/{@code add} exist), so this assertion fails
     * on the pre-migration build.
     */
    @Nested
    @DisplayName("PlayerCacheManager quit-based sweep (GEN-08, D-03)")
    class PlayerCacheSweepTests {

        private PlayerCacheManager liveManager;
        private MockedStatic<UltiTools> ultiToolsStatic;

        @BeforeEach
        void wireLiveManager() {
            liveManager = new PlayerCacheManager();
            UltiTools mockUltiTools = mock(UltiTools.class);
            PluginManager mockPluginManager = mock(PluginManager.class);
            when(mockPluginManager.getPlayerCacheManager()).thenReturn(liveManager);
            when(mockUltiTools.getPluginManager()).thenReturn(mockPluginManager);
            ultiToolsStatic = mockStatic(UltiTools.class);
            ultiToolsStatic.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        }

        @AfterEach
        void closeStaticMock() {
            if (ultiToolsStatic != null) {
                ultiToolsStatic.close();
            }
        }

        @Test
        @DisplayName("A notified player's UUID is gone after they quit, observed through the real quit path")
        void notifiedPlayerUuidGoneAfterRealQuitPath() {
            UUID uuid = UUID.randomUUID();

            // markPlayerNotified() both records the UUID and triggers lazy first-use
            // registration with the live manager wired above.
            updateManager.markPlayerNotified(uuid);
            assertThat(updateManager.isPlayerNotified(uuid)).isTrue();

            liveManager.onPlayerQuit(uuid);

            assertThat(updateManager.isPlayerNotified(uuid))
                    .withFailMessage("nothing removes from notifiedPlayers at HEAD -- this must "
                            + "now be pruned by the real quit path, not merely still present")
                    .isFalse();
        }

        @Test
        @DisplayName("Sweeping the same quitting player twice removes nothing further and throws nothing")
        void sweepingSamePlayerTwiceIsIdempotent() {
            UUID uuid = UUID.randomUUID();
            updateManager.markPlayerNotified(uuid);

            liveManager.onPlayerQuit(uuid);
            assertThatCode(() -> liveManager.onPlayerQuit(uuid)).doesNotThrowAnyException();

            assertThat(updateManager.isPlayerNotified(uuid)).isFalse();
        }
    }
}
