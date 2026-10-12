package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

import com.sun.net.httpserver.HttpServer;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.utils.PluginInstallUtilsTestAccess;
import com.ultikits.ultitools.utils.VersionUtils;

/**
 * The start-up update check (Phase 19, D19-032): it stops when UltiTools disables, and it reports
 * "up to date" only when every lookup confirmed there is nothing newer.
 * <p>
 * Before the fix the check ran on an async task nothing stopped, so a server stopped while it was
 * in flight logged {@code Failed to check framework update: zip file closed} (and per-module
 * classloader zip errors) after {@code Disabling UltiTools}, followed by "All plugins are up to
 * date!". The same "up to date" line followed every failed lookup, also without any stop: an
 * unreachable catalogue, an error status or a malformed body all read as "nothing newer".
 * <br>
 * 启动时的更新检查：UltiTools 禁用时停止；只有每一次查询都确认没有新版本时才报告"全部最新"。
 */
@DisplayName("D19-032: the start-up update check stops on disable and never reports up to date after a failed lookup")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class UpdateCheckShutdownAndOutcomeTest {

    private static final String UP_TO_DATE = "所有插件已是最新版本！";
    private static final String INCOMPLETE = "更新检查未完成：无法确认 %s 的最新版本。";

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    /** What the stub catalogue answers; a test changes these before running the check. */
    private volatile Answer newest = new Answer(200, "{\"code\":\"200\",\"msg\":\"Success\",\"data\":\"6.3.0\"}");
    private volatile Answer pluginGet = new Answer(200,
            "{\"code\":\"200\",\"msg\":\"Success\",\"data\":{\"id\":7,\"identifyString\":\"demo\"}}");
    private volatile Answer latest = new Answer(200, "{\"code\":\"200\",\"msg\":\"Success\",\"data\":\"1.0\"}");
    /** Runs inside the stub while it answers the framework lookup (before the answer is sent). */
    private volatile Runnable onNewest = () -> { };
    /** Runs inside the stub while it answers a module's plugin/get lookup (before the answer is sent). */
    private volatile Runnable onPluginGet = () -> { };
    /** Runs for every record the check logs, after it is captured. */
    private volatile java.util.function.Consumer<LogRecord> onRecord = r -> { };

    private Logger logger;
    private UpdateManager updateManager;
    private UltiToolsPlugin demo;

    private static final class Answer {
        private final int status;
        private final String body;

        private Answer(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            requests.add(path);
            Answer answer;
            if ("/plugin/ultitools/newest".equals(path)) {
                onNewest.run();
                answer = newest;
            } else if ("/plugin/get".equals(path)) {
                onPluginGet.run();
                answer = pluginGet;
            } else if ("/plugin/7/latest".equals(path)) {
                answer = latest;
            } else {
                answer = new Answer(404, "{\"code\":\"404\",\"msg\":\"Not found\"}");
            }
            byte[] bytes = answer.body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answer.status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        PluginInstallUtilsTestAccess.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

        logger = Logger.getLogger("UpdateCheckShutdownAndOutcomeTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
                onRecord.accept(record);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        });
        updateManager = new UpdateManager(logger);

        demo = mock(UltiToolsPlugin.class);
        when(demo.getIdentifyString()).thenReturn("demo");
        when(demo.getPluginName()).thenReturn("Demo");
        when(demo.getVersion()).thenReturn("1.0");
    }

    @AfterEach
    void tearDown() {
        PluginInstallUtilsTestAccess.resetBaseUrl();
        server.stop(0);
    }

    /** Runs the check against the stub with UltiTools 6.3.0 (API level 630) and the given modules. */
    private void check(List<UltiToolsPlugin> modules) {
        try (MockedStatic<UltiTools> ultiMock = mockStatic(UltiTools.class)) {
            stubUltiTools(ultiMock, modules);
            updateManager.checkUpdatesSync();
        }
    }

    private static void stubUltiTools(MockedStatic<UltiTools> ultiMock, List<UltiToolsPlugin> modules) {
        YamlConfiguration env = new YamlConfiguration();
        env.set("version", "6.3.0");
        ultiMock.when(UltiTools::getEnv).thenReturn(env);
        ultiMock.when(UltiTools::getPluginVersion).thenReturn(630);
        UltiTools instance = mock(UltiTools.class);
        PluginManager pm = mock(PluginManager.class);
        when(pm.getPluginList()).thenReturn(modules);
        when(instance.getPluginManager()).thenReturn(pm);
        when(instance.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        ultiMock.when(UltiTools::getInstance).thenReturn(instance);
    }

    private List<String> messagesAtOrAbove(Level level) {
        return records.stream()
                .filter(r -> r.getLevel().intValue() >= level.intValue())
                .map(LogRecord::getMessage)
                .collect(Collectors.toList());
    }

    private String lastInfo() {
        List<String> info = messagesAtOrAbove(Level.INFO);
        return info.isEmpty() ? null : info.get(info.size() - 1);
    }

    /**
     * A local port nothing listens on, so a request to it is refused at once. A closed plain
     * socket is used because a stopped {@code HttpServer} keeps accepting for a while and the
     * request would wait for the 30-second read timeout instead.
     */
    private static int closedPort() throws IOException {
        // Test-only: binds a loopback port and closes it at once; no data is ever sent over it.
        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Nested
    @DisplayName("the check stops when UltiTools disables")
    class Shutdown {

        @Test
        @DisplayName("stop() cancels the scheduled check task")
        void stopCancelsTheScheduledTask() {
            Plugin plugin = mock(Plugin.class);
            Server bukkitServer = mock(Server.class);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            BukkitTask task = mock(BukkitTask.class);
            when(plugin.getServer()).thenReturn(bukkitServer);
            when(bukkitServer.getScheduler()).thenReturn(scheduler);
            when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class))).thenReturn(task);

            updateManager.start(plugin);
            verify(scheduler).runTaskAsynchronously(any(Plugin.class), any(Runnable.class));
            verify(task, never()).cancel();

            updateManager.stop();

            verify(task).cancel();
            assertThat(updateManager.isStopped()).isTrue();
        }

        @Test
        @DisplayName("stop() before start schedules nothing, and a check run after stop() makes no lookup and logs nothing")
        void checkAfterStopDoesNothing() {
            updateManager.stop();

            Plugin plugin = mock(Plugin.class);
            updateManager.start(plugin);
            verify(plugin, never()).getServer();

            check(Collections.singletonList(demo));

            assertThat(requests).isEmpty();
            assertThat(messagesAtOrAbove(Level.INFO)).isEmpty();
            assertThat(updateManager.isCheckComplete()).isFalse();
        }

        @Test
        @DisplayName("disabling during the framework lookup: no module lookup follows, and nothing is logged at INFO or above afterwards")
        void stopDuringALookupEndsTheCheckSilently() {
            onNewest = updateManager::stop;
            newest = new Answer(500, "{\"code\":\"500\",\"msg\":\"down\"}");

            check(Collections.singletonList(demo));

            assertThat(requests).containsExactly("/plugin/ultitools/newest");
            List<String> visible = messagesAtOrAbove(Level.INFO);
            assertThat(visible).hasSize(1);
            assertThat(visible.get(0)).contains("正在检查版本更新");
            assertThat(updateManager.isCheckComplete()).isFalse();
        }

        @Test
        @DisplayName("an exception thrown after disable began (the closed plugin JAR) is logged at FINE, never as a WARNING")
        void failureAfterStopIsFine() {
            try (MockedStatic<UltiTools> ultiMock = mockStatic(UltiTools.class);
                 MockedStatic<VersionUtils> versionMock = mockStatic(VersionUtils.class)) {
                stubUltiTools(ultiMock, Collections.singletonList(demo));
                versionMock.when(VersionUtils::lookUpUltiToolsNewestVersion).thenAnswer(inv -> {
                    updateManager.stop();
                    throw new IllegalStateException("zip file closed");
                });

                assertThatCode(updateManager::checkUpdatesSync).doesNotThrowAnyException();
            }

            assertThat(messagesAtOrAbove(Level.WARNING)).isEmpty();
            assertThat(records.stream().filter(r -> r.getLevel() == Level.FINE)
                    .map(LogRecord::getMessage).collect(Collectors.toList()))
                    .anySatisfy(m -> assertThat(m).contains("zip file closed"));
            assertThat(requests).isEmpty();
        }

        @Test
        @DisplayName("a class that cannot load after disable began (a LinkageError) ends the check at FINE instead of escaping to the scheduler")
        void linkageErrorAfterStopIsFine() {
            try (MockedStatic<UltiTools> ultiMock = mockStatic(UltiTools.class)) {
                stubUltiTools(ultiMock, Collections.singletonList(demo));
                when(UltiTools.getInstance().getPluginManager().getPluginList()).thenAnswer(inv -> {
                    updateManager.stop();
                    throw new NoClassDefFoundError("com/ultikits/ultitools/entities/UpdateInfo");
                });

                assertThatCode(updateManager::checkUpdatesSync).doesNotThrowAnyException();
            }

            assertThat(messagesAtOrAbove(Level.WARNING)).isEmpty();
        }

        @Test
        @DisplayName("disabling between a module's two requests (during plugin/get): its latest request is not sent")
        void stopBetweenAModulesTwoRequestsSendsNoSecond() {
            onPluginGet = updateManager::stop;

            check(Collections.singletonList(demo));

            assertThat(requests).containsExactly("/plugin/ultitools/newest", "/plugin/get");
            assertThat(messagesAtOrAbove(Level.INFO)).hasSize(1);
        }

        @Test
        @DisplayName("disabling while the result lines print: no INFO line follows the one being written")
        void stopWhileResultsPrintSilencesTheRest() {
            newest = new Answer(200, "{\"code\":\"200\",\"msg\":\"Success\",\"data\":\"6.4.0\"}");
            latest = new Answer(200, "{\"code\":\"200\",\"msg\":\"Success\",\"data\":\"1.1\"}");
            onRecord = r -> {
                if (r.getMessage().contains("UltiTools-API有新版本")) {
                    updateManager.stop();
                }
            };

            check(Collections.singletonList(demo));

            List<String> visible = messagesAtOrAbove(Level.INFO);
            assertThat(visible).hasSize(2);
            assertThat(visible.get(1)).contains("6.4.0");
        }

        @Test
        @DisplayName("control: the same exception while UltiTools is running still logs the Failed to check WARNING")
        void failureWhileRunningStillWarns() {
            try (MockedStatic<UltiTools> ultiMock = mockStatic(UltiTools.class);
                 MockedStatic<VersionUtils> versionMock = mockStatic(VersionUtils.class)) {
                stubUltiTools(ultiMock, Collections.<UltiToolsPlugin>emptyList());
                versionMock.when(VersionUtils::lookUpUltiToolsNewestVersion)
                        .thenThrow(new IllegalStateException("zip file closed"));

                updateManager.checkUpdatesSync();
            }

            assertThat(messagesAtOrAbove(Level.WARNING))
                    .containsExactly("[UltiTools-API] Failed to check framework update: zip file closed");
            assertThat(lastInfo()).isEqualTo("[UltiTools-API] " + String.format(INCOMPLETE, "UltiTools-API"));
        }
    }

    @Nested
    @DisplayName("\"up to date\" only when every lookup confirmed nothing newer; otherwise \"incomplete\"")
    class Outcome {

        @Test
        @DisplayName("every lookup answered with the current version: All plugins are up to date")
        void confirmedNoNewerIsUpToDate() {
            check(Collections.singletonList(demo));

            assertThat(requests).containsExactly("/plugin/ultitools/newest", "/plugin/get", "/plugin/7/latest");
            assertThat(lastInfo()).isEqualTo("[UltiTools-API] " + UP_TO_DATE);
            assertThat(updateManager.getUnconfirmed()).isEmpty();
            assertThat(updateManager.isCheckComplete()).isTrue();
        }

        @Test
        @DisplayName("the catalogue unreachable: incomplete, naming the framework and the module, and never up to date")
        void unreachableIsIncomplete() throws IOException {
            PluginInstallUtilsTestAccess.setBaseUrl("http://127.0.0.1:" + closedPort());

            check(Collections.singletonList(demo));

            assertThat(messagesAtOrAbove(Level.INFO)).doesNotContain("[UltiTools-API] " + UP_TO_DATE);
            assertThat(lastInfo()).isEqualTo("[UltiTools-API] " + String.format(INCOMPLETE, "UltiTools-API, Demo"));
            assertThat(updateManager.getUnconfirmed()).containsExactly("UltiTools-API", "Demo");
            assertThat(updateManager.isCheckComplete()).isTrue();
        }

        @Test
        @DisplayName("an error status (500) from the framework lookup: incomplete")
        void errorStatusIsIncomplete() {
            newest = new Answer(500, "{\"code\":\"500\",\"msg\":\"Internal error\"}");

            check(Collections.<UltiToolsPlugin>emptyList());

            assertThat(lastInfo()).isEqualTo("[UltiTools-API] " + String.format(INCOMPLETE, "UltiTools-API"));
        }

        @Test
        @DisplayName("a malformed body (not JSON) from the module's latest lookup: incomplete, naming that module")
        void malformedBodyIsIncomplete() {
            latest = new Answer(200, "<html>gateway</html>");

            check(Collections.singletonList(demo));

            assertThat(lastInfo()).isEqualTo("[UltiTools-API] " + String.format(INCOMPLETE, "Demo"));
        }

        @Test
        @DisplayName("an error envelope (HTTP 200, code 500) from the module lookup: incomplete")
        void errorEnvelopeIsIncomplete() {
            pluginGet = new Answer(200, "{\"code\":\"500\",\"msg\":\"Internal error\"}");

            check(Collections.singletonList(demo));

            assertThat(lastInfo()).isEqualTo("[UltiTools-API] " + String.format(INCOMPLETE, "Demo"));
        }

        @Test
        @DisplayName("an envelope without data from the framework lookup: incomplete")
        void missingDataIsIncomplete() {
            newest = new Answer(200, "{\"code\":\"200\",\"msg\":\"Success\"}");

            check(Collections.<UltiToolsPlugin>emptyList());

            assertThat(lastInfo()).isEqualTo("[UltiTools-API] " + String.format(INCOMPLETE, "UltiTools-API"));
        }

        @Test
        @DisplayName("a module the catalogue does not know (404 from plugin/get) is confirmed: nothing to offer, still up to date")
        void moduleNotInCatalogueIsConfirmed() {
            pluginGet = new Answer(404, "{\"code\":\"404\",\"msg\":\"Plugin not found\"}");

            check(Collections.singletonList(demo));

            assertThat(lastInfo()).isEqualTo("[UltiTools-API] " + UP_TO_DATE);
        }

        @Test
        @DisplayName("no compatible version (409) and no finalized version (404) from latest are confirmed answers: up to date")
        void noOfferAnswersAreConfirmed() {
            latest = new Answer(409, "{\"code\":\"409\",\"msg\":\"No version of this module is compatible\"}");
            check(Collections.singletonList(demo));
            assertThat(lastInfo()).isEqualTo("[UltiTools-API] " + UP_TO_DATE);

            records.clear();
            latest = new Answer(404, "{\"code\":\"404\",\"msg\":\"No versions found for this plugin\"}");
            updateManager = new UpdateManager(logger);
            check(Collections.singletonList(demo));
            assertThat(lastInfo()).isEqualTo("[UltiTools-API] " + UP_TO_DATE);
        }

        @Test
        @DisplayName("an update found for one module while another lookup failed: the update is listed, then incomplete, and no up-to-date line")
        void updateFoundAndAnotherFailed() {
            latest = new Answer(200, "{\"code\":\"200\",\"msg\":\"Success\",\"data\":\"1.1\"}");
            newest = new Answer(503, "{\"code\":\"503\",\"msg\":\"Unavailable\"}");

            check(Collections.singletonList(demo));

            List<String> visible = messagesAtOrAbove(Level.INFO);
            assertThat(visible).contains("[UltiTools-API]   Demo 1.0 -> 1.1");
            assertThat(visible).doesNotContain("[UltiTools-API] " + UP_TO_DATE);
            assertThat(lastInfo()).isEqualTo("[UltiTools-API] " + String.format(INCOMPLETE, "UltiTools-API"));
            assertThat(updateManager.hasAnyUpdates()).isTrue();
        }

        @Test
        @DisplayName("a failed lookup is not a WARNING per lookup: one INFO terminal line, the cause at FINE")
        void failedLookupDetailIsFine() {
            newest = new Answer(500, "{\"code\":\"500\",\"msg\":\"Internal error\"}");

            check(Collections.<UltiToolsPlugin>emptyList());

            assertThat(messagesAtOrAbove(Level.WARNING)).isEmpty();
            assertThat(records.stream().filter(r -> r.getLevel() == Level.FINE).map(LogRecord::getMessage)
                    .collect(Collectors.toList()))
                    .anySatisfy(m -> assertThat(m).contains("UltiTools-API").contains("HTTP 500"));
        }
    }

    @Nested
    @DisplayName("the shipped language files carry the new line")
    class LanguageFiles {

        @Test
        @DisplayName("lang/en.json and lang/zh.json both translate the incomplete line, keeping its one %s")
        void bothLanguageFilesCarryTheKey() throws IOException {
            for (String file : new String[]{"lang/en.json", "lang/zh.json"}) {
                com.google.gson.JsonObject json;
                try (java.io.InputStream in = UpdateManager.class.getClassLoader().getResourceAsStream(file)) {
                    assertThat(in).as(file).isNotNull();
                    json = com.google.gson.JsonParser.parseReader(
                            new java.io.InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                }
                assertThat(json.has(INCOMPLETE)).as(file).isTrue();
                String value = json.get(INCOMPLETE).getAsString();
                assertThat(value.split("%s", -1)).as(file).hasSize(2);
            }
        }

        @Test
        @DisplayName("English reads: Update check incomplete: could not confirm the latest version of %s.")
        void englishWording() throws IOException {
            try (java.io.InputStream in = UpdateManager.class.getClassLoader().getResourceAsStream("lang/en.json")) {
                com.google.gson.JsonObject json = com.google.gson.JsonParser.parseReader(
                        new java.io.InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                assertThat(json.get(INCOMPLETE).getAsString())
                        .isEqualTo("Update check incomplete: could not confirm the latest version of %s.");
            }
        }
    }
}
