package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.ContractFixtures.Fixture;
import com.ultikits.ultitools.contract.ContractSchemas;

/**
 * Wave A's REST traffic against the vendored contract 2.0.0 (plan 22-20, D-36 as corrected, S-64,
 * contract answers 13, 14, 23): the device-authorize and device-token request bodies the client
 * builds validate against their frozen schemas, the legacy server id travels as
 * {@code legacyServerUuid}, and every valid response and error fixture of {@code deviceAuthorize},
 * {@code deviceToken} and {@code credentialRevoke} parses to the outcome its status and code imply.
 * As PROTOCOL.md section 2 states, a response or error file name starts with its HTTP status.
 */
@DisplayName("Wave A REST bodies against the vendored agent-rest contract")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class AgentRestConformanceTest {

    private static final String AUTHORIZE_REQUEST_SCHEMA = "schemas/agent-rest/deviceAuthorize.request.schema.json";
    private static final String TOKEN_REQUEST_SCHEMA = "schemas/agent-rest/deviceToken.request.schema.json";

    @TempDir
    Path tempDir;

    private HttpServer cloud;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Reply> reply = new AtomicReference<>();
    private final List<String> logLines = new CopyOnWriteArrayList<>();
    private final DeviceAuthorizationClient client = new DeviceAuthorizationClient();

    /** One request as the stub saw it. */
    private static final class Recorded {
        final String method;
        final String path;
        final String contentType;
        final String contentLength;
        final byte[] body;

        Recorded(HttpExchange exchange, byte[] body) {
            this.method = exchange.getRequestMethod();
            this.path = exchange.getRequestURI().getPath();
            this.contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            this.contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
            this.body = body;
        }

        JsonObject json() {
            return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /** The stub's answer: a status, a body and an optional Retry-After. */
    private static final class Reply {
        final int status;
        final String body;
        final String retryAfter;

        Reply(int status, String body, String retryAfter) {
            this.status = status;
            this.body = body;
            this.retryAfter = retryAfter;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        MockBukkitHelper.ensureCleanState();
        Logger logger = Logger.getLogger("AgentRestConformanceTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logLines.add(record.getLevel() + " " + record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        TestHelper.mockUltiToolsInstance(ultiTools -> lenient().when(ultiTools.getLogger()).thenReturn(logger));
        CloudTestAccess.useTemporaryCredentials(tempDir);
        reply.set(new Reply(500, "{}", null));
        cloud = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cloud.createContext("/", exchange -> {
            try {
                requests.add(new Recorded(exchange, readBody(exchange)));
                Reply answer = reply.get();
                byte[] bytes = answer.body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                if (answer.retryAfter != null) {
                    exchange.getResponseHeaders().set("Retry-After", answer.retryAfter);
                }
                exchange.sendResponseHeaders(answer.status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } finally {
                exchange.close();
            }
        });
        cloud.start();
        CloudTestAccess.setBaseUrl("http://127.0.0.1:" + cloud.getAddress().getPort());
    }

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the repository's test idiom for the plugin singleton
    void tearDown() throws Exception {
        cloud.stop(0);
        CloudTestAccess.reset();
        MockBukkitHelper.safeUnmock();
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** @return the HTTP status a fixture's file name starts with */
    private static int statusOf(Fixture fixture) {
        String name = fixture.path().substring(fixture.path().lastIndexOf('/') + 1);
        assertThat(name).as("status prefix of " + fixture.path()).matches("\\d{3}-.*");
        return Integer.parseInt(name.substring(0, 3));
    }

    private static String issuedBody() {
        return ContractFixtures.text("fixtures/agent-rest/deviceAuthorize/response/valid/200-issued.json");
    }

    // ------------------------------------------------------------------ device authorize: request

    @ParameterizedTest(name = "framework {0}, name {1}, stored id {2}")
    @CsvSource(value = {
            "6.3.1, Home Server, 123e4567e89b12d3a456426614174000",
            "6.3.1-SNAPSHOT, NULL, NULL",
            "6.3.1, Survival, 123E4567-E89B-12D3-A456-426614174000",
            "6.3.1, NULL, '  123e4567-e89b-12d3-a456-426614174000  '"
    }, nullValues = "NULL")
    @DisplayName("the authorize body validates against the frozen schema and names the legacy id legacyServerUuid")
    void authorizeRequestMatchesTheSchema(String framework, String suggestedName, String storedId) {
        reply.set(new Reply(200, issuedBody(), null));
        assertThat(client.authorize(framework, suggestedName, storedId).isGranted()).isTrue();

        JsonObject body = requests.get(0).json();
        assertThat(requests.get(0).path).isEqualTo("/v1/device/authorize");
        ContractSchemas.assertRestBodyValid("deviceAuthorize.request.schema.json", body);
        assertThat(body.has("attachToServerId")).isFalse();
        assertThat(body.get("framework").getAsString()).isEqualTo(framework);
        if (storedId == null) {
            assertThat(body.has("legacyServerUuid")).isFalse();
        } else {
            assertThat(body.get("legacyServerUuid").getAsString()).isEqualTo("123e4567e89b12d3a456426614174000");
        }
    }

    @Test
    @DisplayName("the vendored hyphenated legacy id stays invalid, and the client never sends it hyphenated")
    void hyphenatedLegacyIdIsNeverSent() {
        Fixture hyphenated = ContractFixtures.restBodies("deviceAuthorize", "request", false).stream()
                .filter(f -> f.path().endsWith("/hyphenated-legacy-uuid.json")).findFirst().get();
        assertThat(ContractSchemas.isValid(AUTHORIZE_REQUEST_SCHEMA, hyphenated.body())).isFalse();
        for (Fixture valid : ContractFixtures.restBodies("deviceAuthorize", "request", true)) {
            assertThat(ContractSchemas.isValid(AUTHORIZE_REQUEST_SCHEMA, valid.body())).as(valid.path()).isTrue();
        }

        reply.set(new Reply(200, issuedBody(), null));
        String raw = hyphenated.body().get("legacyServerUuid").getAsString();
        client.authorize(hyphenated.body().get("framework").getAsString(), null, raw);
        JsonObject sent = requests.get(0).json();
        assertThat(ContractSchemas.isValid(AUTHORIZE_REQUEST_SCHEMA, sent)).isTrue();
        assertThat(sent.get("legacyServerUuid").getAsString()).isEqualTo(LegacyServerId.canonical(raw)).doesNotContain("-");

        reply.set(new Reply(200, issuedBody(), null));
        client.authorize("6.3.1", null, "urn:uuid:not-a-uuid");
        assertThat(requests.get(1).json().has("legacyServerUuid")).as("an unusable id is omitted").isFalse();
    }

    // ------------------------------------------------------------------ device authorize: answers

    static List<Fixture> authorizeAnswers() {
        List<Fixture> all = ContractFixtures.restBodies("deviceAuthorize", "response", true);
        all.addAll(ContractFixtures.restBodies("deviceAuthorize", "error", true));
        return all;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("authorizeAnswers")
    @DisplayName("every valid authorize answer reaches the outcome its status and code imply")
    void authorizeAnswerOutcome(Fixture fixture) {
        int status = statusOf(fixture);
        reply.set(new Reply(status, fixture.body().toString(), status == 429 ? "30" : null));
        DeviceAuthorizationClient.AuthorizeResult result = client.authorize("6.3.1", null, null);
        if (status == 200) {
            assertThat(result.isGranted()).isTrue();
            JsonObject data = fixture.body().getAsJsonObject("data");
            assertThat(result.grant().userCode()).isEqualTo(data.get("userCode").getAsString());
            assertThat(result.grant().verificationUri()).isEqualTo(data.get("verificationUri").getAsString());
            assertThat(result.grant().expiresInSeconds()).isEqualTo(600);
            assertThat(result.grant().intervalSeconds()).isEqualTo(5);
        } else if (status == 429) {
            assertThat(result.isGranted()).isFalse();
            assertThat(result.retryAfterSeconds()).isEqualTo(30);
        } else {
            assertThat(result.isGranted()).isFalse();
            assertThat(result.failureReason()).contains("HTTP " + status)
                    .contains(fixture.body().getAsJsonObject("error").get("code").getAsString());
        }
    }

    @Test
    @DisplayName("a 429 without the Retry-After header still waits details.retryAfterSeconds")
    void rateLimitedReadsTheDetailsWithoutTheHeader() {
        String body = ContractFixtures.text("fixtures/agent-rest/deviceAuthorize/error/valid/429-rate-limited.json");
        reply.set(new Reply(429, body, null));
        assertThat(client.authorize("6.3.1", null, null).retryAfterSeconds()).isEqualTo(30);
    }

    // ------------------------------------------------------------------ device token

    static List<Fixture> tokenAnswers() {
        List<Fixture> all = ContractFixtures.restBodies("deviceToken", "response", true);
        all.addAll(ContractFixtures.restBodies("deviceToken", "error", true));
        return all;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tokenAnswers")
    @DisplayName("every valid token answer reaches the outcome its status and code imply; the poll body is valid")
    void tokenAnswerOutcome(Fixture fixture) {
        int status = statusOf(fixture);
        reply.set(new Reply(status, fixture.body().toString(), null));
        String deviceCode = JsonParser.parseString(issuedBody()).getAsJsonObject().getAsJsonObject("data")
                .get("deviceCode").getAsString();
        DeviceAuthorizationClient.PollResult result = client.pollToken(deviceCode);
        ContractSchemas.assertRestBodyValid("deviceToken.request.schema.json", requests.get(0).json());
        assertThat(ContractSchemas.isValid(TOKEN_REQUEST_SCHEMA, requests.get(0).json())).isTrue();
        if (status == 200) {
            JsonObject data = fixture.body().getAsJsonObject("data");
            if ("server".equals(data.get("kind").getAsString())) {
                assertThat(result.kind()).isEqualTo(DeviceAuthorizationClient.PollResult.Kind.GRANTED);
                assertThat(result.credential().getServerId()).isEqualTo(data.get("serverId").getAsString());
                assertThat(result.credential().getServerName()).isEqualTo(data.get("serverName").getAsString());
                assertThat(result.credential().getCredentialId()).isEqualTo(data.get("credentialId").getAsString());
            } else {
                // A CLI grant cannot link a server: it is never adopted, polling continues.
                assertThat(result.kind()).isEqualTo(DeviceAuthorizationClient.PollResult.Kind.TRANSIENT);
            }
        } else {
            String code = fixture.body().getAsJsonObject("error").get("code").getAsString();
            DeviceAuthorizationClient.PollResult.Kind expected;
            switch (code) {
                case "authorization_pending":
                    expected = DeviceAuthorizationClient.PollResult.Kind.PENDING;
                    break;
                case "slow_down":
                    expected = DeviceAuthorizationClient.PollResult.Kind.SLOW_DOWN;
                    break;
                case "expired_token":
                    expected = DeviceAuthorizationClient.PollResult.Kind.EXPIRED;
                    break;
                case "access_denied":
                    expected = DeviceAuthorizationClient.PollResult.Kind.DENIED;
                    break;
                default:
                    expected = DeviceAuthorizationClient.PollResult.Kind.REJECTED;
                    break;
            }
            assertThat(result.kind()).isEqualTo(expected);
        }
        assertThat(logLines).noneMatch(line -> line.contains("ucs_AAAA"));
    }

    @Test
    @DisplayName("control: the fixtures cover every RFC 8628 code and both token kinds")
    void tokenFixturesAreComplete() {
        assertThat(tokenAnswers()).extracting(Fixture::path).anyMatch(p -> p.endsWith("200-server.json"))
                .anyMatch(p -> p.endsWith("200-cli.json")).anyMatch(p -> p.endsWith("400-authorization-pending.json"))
                .anyMatch(p -> p.endsWith("400-slow-down.json")).anyMatch(p -> p.endsWith("400-expired-token.json"))
                .anyMatch(p -> p.endsWith("400-access-denied.json"));
    }

    @Test
    @DisplayName("no response or error fixture, valid or invalid, makes a client throw")
    void noFixtureThrows() {
        for (String operation : new String[]{"deviceAuthorize", "deviceToken"}) {
            for (String part : new String[]{"response", "error"}) {
                for (boolean valid : new boolean[]{true, false}) {
                    for (Fixture fixture : ContractFixtures.restBodies(operation, part, valid)) {
                        reply.set(new Reply(statusOf(fixture), fixture.body().toString(), null));
                        assertThatCode(() -> {
                            if ("deviceAuthorize".equals(operation)) {
                                client.authorize("6.3.1", null, null);
                            } else {
                                client.pollToken("dc_1");
                            }
                        }).as(fixture.path()).doesNotThrowAnyException();
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ credential revoke

    static List<Fixture> revokeAnswers() {
        List<Fixture> all = ContractFixtures.restBodies("credentialRevoke", "response", true);
        all.addAll(ContractFixtures.restBodies("credentialRevoke", "error", true));
        return all;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("revokeAnswers")
    @DisplayName("every valid revoke answer counts as revoked; the revoke request has no body")
    void revokeAnswerOutcome(Fixture fixture) throws InterruptedException {
        MockBukkit.mock();
        reply.set(new Reply(statusOf(fixture), fixture.body().toString(), null));
        CloudTestAccess.storeLinkedCredential("Survival", HandshakeEndToEndTest.SERVER_ID);
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();

        List<CloudAuthManager.LogoutOutcome> outcomes = new CopyOnWriteArrayList<>();
        CloudAuthManager.logoutReporting(outcomes::add);
        // The revoke runs on a Bukkit async task; MockBukkit's waitAsyncTasksFinished() can return before it starts.
        CloudLogoutRevokeTest.awaitOutcome(outcomes);

        assertThat(outcomes).containsExactly(CloudAuthManager.LogoutOutcome.REVOKED);
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).path).isEqualTo("/v1/agent/credential/revoke");
        assertThat(requests.get(0).method).isEqualTo("POST");
        assertThat(requests.get(0).body).as("no request body").isEmpty();
        assertThat(requests.get(0).contentLength).isEqualTo("0");
        assertThat(requests.get(0).contentType).isIn(null, "application/x-www-form-urlencoded");
    }

    @Test
    @DisplayName("control: the revoke fixtures are the 200 and 401 answers of contract answer 14")
    void revokeFixturesAreComplete() {
        assertThat(revokeAnswers()).extracting(AgentRestConformanceTest::statusOf).containsExactlyInAnyOrder(200, 401);
    }
}
