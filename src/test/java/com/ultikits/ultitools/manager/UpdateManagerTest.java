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
     * #431: {@code notifiedPlayers}'s lifetime is the manager instance's own lifetime (one server
     * run), NOT the player's connection. GEN-08/D-03 (plan 05-04) had registered this field with
     * {@link PlayerCacheManager} for quit-based sweeping to bound its size; that made a
     * quitting-and-rejoining player look never-notified within the SAME server run, breaking
     * {@link com.ultikits.ultitools.listeners.UpdateJoinListener}'s own documented promise of one
     * notification per player per server session. This class asserts the reversed contract, and
     * -- the guard against a fix that exempts too much -- that a REAL, unrelated
     * {@code @PlayerCache} field on a different bean is still swept by the very same quit path.
     */
    @Nested
    @DisplayName("Notified-player state lifetime is the server run, not the connection (#431)")
    class NotifiedStateLifetimeTests {

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
        @DisplayName("Behavior 1: a notified player's UUID survives the real quit path -- no second "
                + "notification is possible after a quit-and-rejoin within the same server run")
        void notifiedPlayerSurvivesRealQuitPath() {
            UUID uuid = UUID.randomUUID();

            updateManager.markPlayerNotified(uuid);
            assertThat(updateManager.isPlayerNotified(uuid)).isTrue();

            liveManager.onPlayerQuit(uuid);

            assertThat(updateManager.isPlayerNotified(uuid))
                    .withFailMessage("notifiedPlayers must survive a quit within the same server "
                            + "run, or a rejoining player is wrongly notified a second time (#431)")
                    .isTrue();
        }

        @Test
        @DisplayName("Behavior 1 (idempotency): sweeping the same quitting player twice leaves the "
                + "notified mark intact and throws nothing")
        void sweepingSamePlayerTwiceLeavesNotifiedMarkIntact() {
            UUID uuid = UUID.randomUUID();
            updateManager.markPlayerNotified(uuid);

            liveManager.onPlayerQuit(uuid);
            assertThatCode(() -> liveManager.onPlayerQuit(uuid)).doesNotThrowAnyException();

            assertThat(updateManager.isPlayerNotified(uuid)).isTrue();
        }

        /**
         * A minimal {@code @PlayerCache} fixture, structurally identical to real per-connection
         * state (e.g. {@code InMemeryTeleportService}'s tracked-players set) that legitimately
         * must still be cleared on quit.
         */
        class UnrelatedPerPlayerCacheBean {
            @com.ultikits.ultitools.annotations.PlayerCache
            final Set<UUID> activeSessions = new HashSet<>();
        }

        @Test
        @DisplayName("Behavior 4: the same quit path still sweeps an UNRELATED bean's real "
                + "@PlayerCache field -- this fix does not disable the sweep mechanism generally")
        void quitStillSweepsUnrelatedPlayerCacheField() {
            UUID uuid = UUID.randomUUID();
            UnrelatedPerPlayerCacheBean unrelatedBean = new UnrelatedPerPlayerCacheBean();
            unrelatedBean.activeSessions.add(uuid);
            liveManager.registerBean(unrelatedBean);

            updateManager.markPlayerNotified(uuid);

            liveManager.onPlayerQuit(uuid);

            assertThat(unrelatedBean.activeSessions)
                    .as("PlayerCacheManager's general sweep must still clear a genuinely "
                            + "connection-scoped @PlayerCache field")
                    .doesNotContain(uuid);
            assertThat(updateManager.isPlayerNotified(uuid))
                    .as("...while UpdateManager's own notified mark, which is no longer "
                            + "@PlayerCache-annotated, is untouched by the same quit event")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Restart resets notified state (#431)")
    class RestartResetsStateTests {

        @Test
        @DisplayName("Behavior 5: a first-ever join on a freshly started server (a new UpdateManager "
                + "instance) behaves as a first join -- notified state does not survive a restart")
        void freshInstanceHasNoMemoryOfAnEarlierInstancesNotifications() {
            UUID uuid = UUID.randomUUID();
            updateManager.markPlayerNotified(uuid);
            assertThat(updateManager.isPlayerNotified(uuid)).isTrue();

            // UltiTools.scheduleStartupMessages() builds exactly one fresh UpdateManager per
            // onEnable(), so a restart is modelled here as constructing a brand-new instance.
            UpdateManager afterRestart = new UpdateManager(mock(Logger.class));

            assertThat(afterRestart.isPlayerNotified(uuid))
                    .as("a new server run must start with no players marked notified")
                    .isFalse();
        }
    }
}
