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
import com.ultikits.ultitools.utils.VersionLookup;
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

                versionMock.when(VersionUtils::lookUpUltiToolsNewestVersion).thenReturn(VersionLookup.found("6.3.0"));

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

                versionMock.when(VersionUtils::lookUpUltiToolsNewestVersion).thenReturn(VersionLookup.found("6.3.0"));

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

                versionMock.when(VersionUtils::lookUpUltiToolsNewestVersion).thenReturn(VersionLookup.failed("HTTP 500"));

                updateManager.checkUpdatesSync();

                assertThat(updateManager.getFrameworkUpdate()).isNull();
                assertThat(updateManager.getUnconfirmed()).containsExactly(UpdateManager.FRAMEWORK_NAME);
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

                versionMock.when(VersionUtils::lookUpUltiToolsNewestVersion).thenReturn(VersionLookup.found("6.2.0"));
                installMock.when(() -> PluginInstallUtils.lookUpPluginLatestVersion(eq("ultichat"), any()))
                    .thenReturn(VersionLookup.found("1.1.0"));

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

                versionMock.when(VersionUtils::lookUpUltiToolsNewestVersion).thenReturn(VersionLookup.found("6.2.0"));

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

    /**
     * The module update check reports only versions this framework can install (plan 18-40; the
     * maintainer's ruling of 2026-10-11): it asks the catalogue's level-filtered {@code latest}, and
     * a 409 (no compatible version) is no offer -- never "needs a newer framework".
     */
    @Nested
    @DisplayName("module update check asks with the API level and offers only what this framework installs (18-40)")
    class ApiLevelUpdateCheck {

        private com.sun.net.httpserver.HttpServer server;
        private final List<String> latestRequests = Collections.synchronizedList(new ArrayList<>());
        private int latestStatus;

        @BeforeEach
        void startCatalogue() throws java.io.IOException {
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getRawPath();
                String query = exchange.getRequestURI().getRawQuery();
                int status = 200;
                String body;
                if ("/plugin/get".equals(path)) {
                    body = "{\"code\":\"200\",\"msg\":\"Success\",\"data\":{\"id\":7,\"identifyString\":\"demo\"}}";
                } else if ("/plugin/7/latest".equals(path)) {
                    latestRequests.add(query);
                    status = latestStatus;
                    body = status == 200 ? "{\"code\":\"200\",\"msg\":\"Success\",\"data\":\"1.1\"}"
                            : "{\"code\":\"409\",\"msg\":\"No version of this module is compatible\"}";
                } else {
                    status = 404;
                    body = "{\"code\":\"404\",\"msg\":\"Not found\"}";
                }
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            com.ultikits.ultitools.utils.PluginInstallUtilsTestAccess.setBaseUrl(
                    "http://127.0.0.1:" + server.getAddress().getPort());
        }

        @AfterEach
        void stopCatalogue() {
            com.ultikits.ultitools.utils.PluginInstallUtilsTestAccess.resetBaseUrl();
            server.stop(0);
        }

        private void check() {
            try (MockedStatic<VersionUtils> versionMock = mockStatic(VersionUtils.class);
                 MockedStatic<UltiTools> ultiMock = mockStatic(UltiTools.class)) {
                org.bukkit.configuration.file.YamlConfiguration env = new org.bukkit.configuration.file.YamlConfiguration();
                env.set("version", "6.3.0");
                ultiMock.when(UltiTools::getEnv).thenReturn(env);
                ultiMock.when(UltiTools::getPluginVersion).thenReturn(630);
                UltiTools instance = mock(UltiTools.class);
                UltiToolsPlugin demo = mock(UltiToolsPlugin.class);
                when(demo.getIdentifyString()).thenReturn("demo");
                when(demo.getPluginName()).thenReturn("Demo");
                when(demo.getVersion()).thenReturn("1.0");
                PluginManager pm = mock(PluginManager.class);
                when(pm.getPluginList()).thenReturn(Collections.singletonList(demo));
                when(instance.getPluginManager()).thenReturn(pm);
                when(instance.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
                ultiMock.when(UltiTools::getInstance).thenReturn(instance);
                versionMock.when(VersionUtils::lookUpUltiToolsNewestVersion).thenReturn(VersionLookup.found("6.3.0"));

                updateManager.checkUpdatesSync();
            }
        }

        @Test
        @DisplayName("the latest request carries apiLevel=630 and minApiLevel=630, and a compatible newer version is offered")
        void compatibleNewerVersion_isOfferedAndTheRequestCarriesTheLevel() {
            latestStatus = 200;

            check();

            assertThat(latestRequests).hasSize(1);
            assertThat(latestRequests.get(0)).contains("apiLevel=630").contains("minApiLevel=630");
            assertThat(updateManager.getModuleUpdates()).containsKey("Demo");
            assertThat(updateManager.getModuleUpdates().get("Demo").getLatestVersion()).isEqualTo("1.1");
        }

        @Test
        @DisplayName("the catalogue answering 409 (no compatible version): no offer, and nothing about a newer framework")
        void noCompatibleVersion_isNoOffer() {
            latestStatus = 409;

            check();

            assertThat(latestRequests).hasSize(1);
            assertThat(updateManager.getModuleUpdates()).isEmpty();
            assertThat(updateManager.hasAnyUpdates()).isFalse();
        }
    }
}
