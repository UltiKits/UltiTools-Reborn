package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.sun.net.httpserver.HttpServer;

/**
 * The two public catalogue lookups keep their contract (a version, or {@code null} for every kind
 * of failure, no exception for an HTTP or body failure), while the internal lookups the start-up
 * update check uses tell "nothing newer is offered" apart from "the lookup failed" (Phase 19,
 * D19-032). No module calls the public methods, but they are public API on a published class.
 * <br>
 * 两个公开查询方法的约定不变；内部查询区分"没有可提供的版本"与"查询失败"。
 */
@DisplayName("D19-032: public version lookups unchanged; internal lookups tell 'none offered' from 'failed'")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class VersionLookupContractTest {

    private HttpServer server;
    private volatile int status;
    private volatile String body;
    private volatile int getStatus;
    private volatile String getBody;

    @BeforeEach
    void setUp() throws IOException {
        status = 200;
        body = ok("\"6.3.1\"");
        getStatus = 200;
        getBody = ok("{\"id\":7,\"identifyString\":\"demo\"}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            int s;
            String b;
            if ("/plugin/get".equals(path)) {
                s = getStatus;
                b = getBody;
            } else {
                s = status;
                b = body;
            }
            byte[] bytes = b.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(s, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        PluginInstallUtils.setBaseUrlForTesting("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        PluginInstallUtils.resetBaseUrl();
        server.stop(0);
    }

    private static String ok(String data) {
        return "{\"code\":\"200\",\"msg\":\"Success\",\"data\":" + data + "}";
    }

    private void answer(int s, String b) {
        status = s;
        body = b;
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
    @DisplayName("VersionUtils#getUltiToolsNewestVersion: public contract unchanged")
    class FrameworkPublic {

        @Test
        @DisplayName("signature: public static String, no parameters, no checked exception")
        void signature() throws Exception {
            Method m = VersionUtils.class.getMethod("getUltiToolsNewestVersion");
            assertThat(Modifier.isStatic(m.getModifiers())).isTrue();
            assertThat(m.getReturnType()).isEqualTo(String.class);
            assertThat(m.getExceptionTypes()).isEmpty();
        }

        @Test
        @DisplayName("a version string is returned as is; a non-primitive data is returned as its JSON text")
        void versionReturned() {
            assertThat(VersionUtils.getUltiToolsNewestVersion()).isEqualTo("6.3.1");
            answer(200, ok("{\"v\":1}"));
            assertThat(VersionUtils.getUltiToolsNewestVersion()).isEqualTo("{\"v\":1}");
        }

        @Test
        @DisplayName("null for an error status, an error envelope, missing data, a malformed body and an unreachable catalogue")
        void nullForEveryFailure() throws IOException {
            answer(500, "{\"code\":\"500\"}");
            assertThat(VersionUtils.getUltiToolsNewestVersion()).isNull();
            answer(200, "{\"code\":\"500\",\"msg\":\"x\"}");
            assertThat(VersionUtils.getUltiToolsNewestVersion()).isNull();
            answer(200, "{\"code\":\"200\",\"msg\":\"Success\"}");
            assertThat(VersionUtils.getUltiToolsNewestVersion()).isNull();
            answer(200, "not json");
            assertThat(VersionUtils.getUltiToolsNewestVersion()).isNull();
            PluginInstallUtils.setBaseUrlForTesting("http://127.0.0.1:" + closedPort());
            assertThat(VersionUtils.getUltiToolsNewestVersion()).isNull();
        }
    }

    @Nested
    @DisplayName("PluginInstallUtils#getPluginLatestVersion: public contract unchanged")
    class ModulePublic {

        @Test
        @DisplayName("signature: public static String getPluginLatestVersion(String), no checked exception")
        void signature() throws Exception {
            Method m = PluginInstallUtils.class.getMethod("getPluginLatestVersion", String.class);
            assertThat(Modifier.isStatic(m.getModifiers())).isTrue();
            assertThat(m.getReturnType()).isEqualTo(String.class);
            assertThat(m.getExceptionTypes()).isEmpty();
        }

        @Test
        @DisplayName("the latest version is returned")
        void versionReturned() {
            answer(200, ok("\"1.2\""));
            assertThat(PluginInstallUtils.getPluginLatestVersion("demo")).isEqualTo("1.2");
        }

        @Test
        @DisplayName("null for a blank id, an unknown module, 409, 404, 500 and a malformed body")
        void nullForEveryOtherAnswer() {
            assertThat(PluginInstallUtils.getPluginLatestVersion("  ")).isNull();
            answer(409, "{\"code\":\"409\"}");
            assertThat(PluginInstallUtils.getPluginLatestVersion("demo")).isNull();
            answer(404, "{\"code\":\"404\"}");
            assertThat(PluginInstallUtils.getPluginLatestVersion("demo")).isNull();
            answer(500, "{\"code\":\"500\"}");
            assertThat(PluginInstallUtils.getPluginLatestVersion("demo")).isNull();
            answer(200, "not json");
            assertThat(PluginInstallUtils.getPluginLatestVersion("demo")).isNull();
            answer(200, ok("\"1.2\""));
            getStatus = 404;
            getBody = "{\"code\":\"404\"}";
            assertThat(PluginInstallUtils.getPluginLatestVersion("demo")).isNull();
            getStatus = 500;
            assertThat(PluginInstallUtils.getPluginLatestVersion("demo")).isNull();
        }
    }

    @Nested
    @DisplayName("internal lookups classify the answer")
    class Internal {

        @Test
        @DisplayName("framework: found, or failed for status / envelope / missing data / body / unreachable")
        void framework() throws IOException {
            VersionLookup found = VersionUtils.lookUpUltiToolsNewestVersion();
            assertThat(found.getOutcome()).isEqualTo(VersionLookup.Outcome.FOUND);
            assertThat(found.getVersion()).isEqualTo("6.3.1");

            answer(500, "{\"code\":\"500\"}");
            assertThat(VersionUtils.lookUpUltiToolsNewestVersion().getFailure()).isEqualTo("HTTP 500");
            answer(200, "{\"code\":\"500\"}");
            assertThat(VersionUtils.lookUpUltiToolsNewestVersion().isFailed()).isTrue();
            answer(200, "{\"code\":\"200\"}");
            assertThat(VersionUtils.lookUpUltiToolsNewestVersion().isFailed()).isTrue();
            answer(200, "not json");
            assertThat(VersionUtils.lookUpUltiToolsNewestVersion().isFailed()).isTrue();
            PluginInstallUtils.setBaseUrlForTesting("http://127.0.0.1:" + closedPort());
            VersionLookup unreachable = VersionUtils.lookUpUltiToolsNewestVersion();
            assertThat(unreachable.isFailed()).isTrue();
            assertThat(unreachable.getVersion()).isNull();
        }

        @Test
        @DisplayName("module: found; none for 404 / 409 from latest and 404 from plugin/get; failed otherwise")
        void module() {
            answer(200, ok("\"1.2\""));
            assertThat(PluginInstallUtils.lookUpPluginLatestVersion("demo").getVersion()).isEqualTo("1.2");

            answer(409, "{\"code\":\"409\"}");
            assertThat(PluginInstallUtils.lookUpPluginLatestVersion("demo").getOutcome())
                    .isEqualTo(VersionLookup.Outcome.NONE);
            answer(404, "{\"code\":\"404\"}");
            assertThat(PluginInstallUtils.lookUpPluginLatestVersion("demo").getOutcome())
                    .isEqualTo(VersionLookup.Outcome.NONE);
            answer(502, "bad gateway");
            assertThat(PluginInstallUtils.lookUpPluginLatestVersion("demo").getFailure()).isEqualTo("HTTP 502");
            answer(200, "{\"code\":\"200\"}");
            assertThat(PluginInstallUtils.lookUpPluginLatestVersion("demo").isFailed()).isTrue();

            answer(200, ok("\"1.2\""));
            getStatus = 404;
            getBody = "{\"code\":\"404\"}";
            assertThat(PluginInstallUtils.lookUpPluginLatestVersion("demo").getOutcome())
                    .isEqualTo(VersionLookup.Outcome.NONE);
            getStatus = 500;
            assertThat(PluginInstallUtils.lookUpPluginLatestVersion("demo").isFailed()).isTrue();
            getStatus = 200;
            getBody = "garbage";
            assertThat(PluginInstallUtils.lookUpPluginLatestVersion("demo").isFailed()).isTrue();
        }
    }
}
