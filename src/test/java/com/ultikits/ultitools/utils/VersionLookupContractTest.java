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
import com.ultikits.ultitools.contract.PendingFixture;
import com.ultikits.ultitools.stub.StubCatalogue;

/**
 * The two public catalogue lookups keep their contract (a version, or {@code null} for every kind
 * of failure, no exception for an HTTP or body failure), while the internal lookups the start-up
 * update check uses tell "nothing newer is offered" apart from "the lookup failed" (Phase 19,
 * D19-032). Both now read the v2 catalogue (plan 22-18): {@code /v1/catalogue/framework} for the
 * framework and {@code /v1/catalogue/modules/:moduleRef} plus its level-filtered {@code versions}
 * for a module, each answering {@code {data: ...}}.
 */
@DisplayName("D19-032: public version lookups unchanged; internal lookups tell 'none offered' from 'failed'")
@PendingFixture
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class VersionLookupContractTest {

    private static final String MODULE = "/v1/catalogue/modules/demo";
    private static final String VERSIONS = MODULE + "/versions";

    private HttpServer server;
    private volatile int moduleStatus;
    private volatile String moduleBody;
    private volatile int versionsStatus;
    private volatile String versionsBody;
    private volatile int frameworkStatus;
    private volatile String frameworkBody;

    @BeforeEach
    void setUp() throws IOException {
        frameworkStatus = 200;
        frameworkBody = StubCatalogue.sample("framework.json");
        moduleStatus = 200;
        moduleBody = StubCatalogue.sample("module.json");
        versionsStatus = 200;
        versionsBody = pageWith("1.2");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            int status;
            String body;
            if (MODULE.equals(path)) {
                status = moduleStatus;
                body = moduleBody;
            } else if (VERSIONS.equals(path)) {
                status = versionsStatus;
                body = versionsBody;
            } else {
                status = frameworkStatus;
                body = frameworkBody;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
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

    private static String data(String payload) {
        return "{\"data\":" + payload + "}";
    }

    /** The pending versions page's shape carrying one version of the sample module. */
    private static String pageWith(String version) {
        return StubCatalogue.versionsPageOf(StubCatalogue.versionSample(version,
                "/v1/catalogue/modules/demo/versions/" + version + "/download"));
    }

    /**
     * A local port nothing listens on, so a request to it is refused at once. A closed plain
     * socket is used because a stopped {@code HttpServer} keeps accepting for a while and the
     * request would wait for the read timeout instead.
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
        @DisplayName("the version the framework route names is returned")
        void versionReturned() {
            assertThat(VersionUtils.getUltiToolsNewestVersion()).isEqualTo("6.3.1");
            frameworkBody = data("{\"version\":\"6.4.0\"}");
            assertThat(VersionUtils.getUltiToolsNewestVersion()).isEqualTo("6.4.0");
        }

        @Test
        @DisplayName("null for an error status, data without a version, a malformed body and an unreachable catalogue")
        void nullForEveryFailure() throws IOException {
            frameworkStatus = 500;
            frameworkBody = "{\"error\":{\"code\":\"internal_error\"}}";
            assertThat(VersionUtils.getUltiToolsNewestVersion()).isNull();
            frameworkStatus = 200;
            frameworkBody = data("{\"sizeBytes\":10}");
            assertThat(VersionUtils.getUltiToolsNewestVersion()).isNull();
            frameworkBody = data("\"6.3.1\"");
            assertThat(VersionUtils.getUltiToolsNewestVersion()).isNull();
            frameworkBody = "not json";
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
        @DisplayName("the version the level-filtered list marks as newest is returned")
        void versionReturned() {
            assertThat(PluginInstallUtils.getPluginLatestVersion("demo")).isEqualTo("1.2");
        }

        @Test
        @DisplayName("null for a blank id, an id that cannot be a module reference, an unknown module, an"
                + " empty list, an error status and a malformed body")
        void nullForEveryOtherAnswer() {
            assertThat(PluginInstallUtils.getPluginLatestVersion("  ")).isNull();
            assertThat(PluginInstallUtils.getPluginLatestVersion("bad name+/")).isNull();
            moduleStatus = 404;
            moduleBody = "{\"error\":{\"code\":\"not_found\"}}";
            assertThat(PluginInstallUtils.getPluginLatestVersion("demo")).isNull();
            moduleStatus = 500;
            assertThat(PluginInstallUtils.getPluginLatestVersion("demo")).isNull();
            moduleStatus = 200;
            moduleBody = "not json";
            assertThat(PluginInstallUtils.getPluginLatestVersion("demo")).isNull();
            moduleBody = data("{\"identifier\":\"demo\"}");
            versionsBody = StubCatalogue.versionsPageOf();
            assertThat(PluginInstallUtils.getPluginLatestVersion("demo")).isNull();
            versionsStatus = 500;
            versionsBody = "{\"error\":{\"code\":\"internal_error\"}}";
            assertThat(PluginInstallUtils.getPluginLatestVersion("demo")).isNull();
        }
    }

    @Nested
    @DisplayName("internal lookups classify the answer")
    class Internal {

        @Test
        @DisplayName("framework: found, or failed for status, data without a version, a malformed body, unreachable")
        void framework() throws IOException {
            VersionLookup found = VersionUtils.lookUpUltiToolsNewestVersion();
            assertThat(found.getOutcome()).isEqualTo(VersionLookup.Outcome.FOUND);
            assertThat(found.getVersion()).isEqualTo("6.3.1");

            frameworkStatus = 500;
            frameworkBody = "{\"error\":{\"code\":\"internal_error\"}}";
            assertThat(VersionUtils.lookUpUltiToolsNewestVersion().getFailure()).isEqualTo("HTTP 500");
            frameworkStatus = 200;
            frameworkBody = "{\"error\":{\"code\":\"internal_error\"}}";
            assertThat(VersionUtils.lookUpUltiToolsNewestVersion().isFailed()).isTrue();
            frameworkBody = data("{}");
            assertThat(VersionUtils.lookUpUltiToolsNewestVersion().isFailed()).isTrue();
            frameworkBody = "not json";
            assertThat(VersionUtils.lookUpUltiToolsNewestVersion().isFailed()).isTrue();
            PluginInstallUtils.setBaseUrlForTesting("http://127.0.0.1:" + closedPort());
            VersionLookup unreachable = VersionUtils.lookUpUltiToolsNewestVersion();
            assertThat(unreachable.isFailed()).isTrue();
            assertThat(unreachable.getVersion()).isNull();
        }

        @Test
        @DisplayName("module: found; none for an unknown module and for an empty list; failed otherwise")
        void module() {
            assertThat(PluginInstallUtils.lookUpPluginLatestVersion("demo").getVersion()).isEqualTo("1.2");

            versionsBody = StubCatalogue.versionsPageOf();
            assertThat(PluginInstallUtils.lookUpPluginLatestVersion("demo").getOutcome())
                    .isEqualTo(VersionLookup.Outcome.NONE);
            versionsBody = pageWith("1.2");
            moduleStatus = 404;
            assertThat(PluginInstallUtils.lookUpPluginLatestVersion("demo").getOutcome())
                    .isEqualTo(VersionLookup.Outcome.NONE);
            moduleStatus = 200;
            versionsStatus = 502;
            versionsBody = "bad gateway";
            assertThat(PluginInstallUtils.lookUpPluginLatestVersion("demo").getFailure()).isEqualTo("HTTP 502");
            versionsStatus = 200;
            versionsBody = data("{}");
            assertThat(PluginInstallUtils.lookUpPluginLatestVersion("demo").isFailed()).isTrue();
            versionsBody = "garbage";
            assertThat(PluginInstallUtils.lookUpPluginLatestVersion("demo").isFailed()).isTrue();
        }

        @Test
        @DisplayName("module: a stop signal read after the module answered sends no versions request")
        void stoppedBetweenTheTwoRequests() {
            assertThat(PluginInstallUtils.lookUpPluginLatestVersion("demo", () -> true).getFailure())
                    .isEqualTo("stopped");
        }
    }
}
