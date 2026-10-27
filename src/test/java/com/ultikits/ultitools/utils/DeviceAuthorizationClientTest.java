package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
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
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.ultikits.ultitools.UltiTools;

/**
 * {@link DeviceAuthorizationClient} and {@link CloudHttp} against a loopback stub: the RFC 8628
 * outcome mapping of {@code POST /v1/device/token} (D-04), the 429 / {@code Retry-After} handling of
 * both calls (contract answer 13), redirects never followed, and the empty POST.
 */
@DisplayName("DeviceAuthorizationClient: RFC 8628 outcomes, 429 and Retry-After, no redirects")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class DeviceAuthorizationClientTest {

    private static final String SECRET = "ucs_TESTSECRET";

    @TempDir
    Path tempDir;

    private HttpServer cloud;
    private HttpServer other;
    private final List<Recorded> cloudRequests = new CopyOnWriteArrayList<>();
    private final List<Recorded> otherRequests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Responder> responder = new AtomicReference<>();
    private final List<String> logLines = new CopyOnWriteArrayList<>();
    private final List<LogRecord> logRecords = new CopyOnWriteArrayList<>();
    private final DeviceAuthorizationClient client = new DeviceAuthorizationClient();

    /** One request as the stub saw it on the wire. */
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

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
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

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Responder answer(int status, String body) {
        return exchange -> send(exchange, status, body);
    }

    private static Responder answerWithRetryAfter(int status, String retryAfter, String body) {
        return exchange -> {
            exchange.getResponseHeaders().set("Retry-After", retryAfter);
            send(exchange, status, body);
        };
    }

    private static String error(String code, String message) {
        return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"requestId\":\"r-1\"}}";
    }

    private static HttpServer start(List<Recorded> sink, AtomicReference<Responder> answer) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                sink.add(new Recorded(exchange, readBody(exchange)));
                answer.get().respond(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @BeforeEach
    void setUp() throws IOException {
        // CloudHttp refuses the server's primary thread; make sure no leaked MockBukkit server turns
        // this JUnit thread into one.
        MockBukkitHelper.ensureCleanState();
        Logger logger = Logger.getLogger("DeviceAuthorizationClientTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logLines.add(record.getLevel() + " " + record.getMessage());
                logRecords.add(record);
            }

            @Override
            public void flush() {
                // Nothing buffered.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        });
        TestHelper.mockUltiToolsInstance(ultiTools -> lenient().when(ultiTools.getLogger()).thenReturn(logger));
        CredentialStore.setTargetPathForTesting(tempDir.resolve("credentials.json"));
        CredentialStore.setOldLocationForTesting(tempDir.resolve("pre-migration-data.json"));
        responder.set(answer(500, "{}"));
        cloud = start(cloudRequests, responder);
        AtomicReference<Responder> otherAnswer = new AtomicReference<>(answer(200, "{\"data\":{\"credential\":\""
                + SECRET + "\",\"serverId\":\"stolen\"}}"));
        other = start(otherRequests, otherAnswer);
        HttpRequestUtils.setBaseUrlForTesting(baseUrl(cloud));
    }

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // clears the UltiTools singleton mocked in setUp
    void tearDown() throws Exception {
        cloud.stop(0);
        other.stop(0);
        HttpRequestUtils.resetBaseUrl();
        CredentialStore.clearTargetPathForTesting();
        CredentialStore.clearOldLocationForTesting();
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    // ---- token poll: the D-04 outcome mapping ----

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "authorization_pending, PENDING",
        "slow_down, SLOW_DOWN",
        "expired_token, EXPIRED",
        "access_denied, DENIED",
        "invalid_request, REJECTED",
        "something_new, REJECTED"
    })
    @DisplayName("each 400 error code maps to its outcome")
    void eachErrorCodeMapsToItsKind(String code, DeviceAuthorizationClient.PollResult.Kind expected) {
        responder.set(answer(400, error(code, "msg for " + code)));

        DeviceAuthorizationClient.PollResult result = client.pollToken("dc-1");

        assertThat(result.kind()).isEqualTo(expected);
        if (expected == DeviceAuthorizationClient.PollResult.Kind.REJECTED) {
            assertThat(result.code()).isEqualTo(code);
            assertThat(result.message()).isEqualTo("msg for " + code);
        }
    }

    @Test
    @DisplayName("slow_down maps to SLOW_DOWN")
    void slowDownMapsToSlowDown() {
        responder.set(answer(400, error("slow_down", "poll less often")));

        assertThat(client.pollToken("dc-1").kind()).isEqualTo(DeviceAuthorizationClient.PollResult.Kind.SLOW_DOWN);
    }

    @Test
    @DisplayName("the token poll body is exactly {deviceCode}")
    void tokenPollBodyIsTheDeviceCodeOnly() {
        responder.set(answer(400, error("authorization_pending", "wait")));

        client.pollToken("dc-1");

        assertThat(cloudRequests).hasSize(1);
        assertThat(cloudRequests.get(0).path).isEqualTo("/v1/device/token");
        assertThat(cloudRequests.get(0).method).isEqualTo("POST");
        assertThat(cloudRequests.get(0).contentType).isEqualTo("application/json; charset=utf-8");
        assertThat(cloudRequests.get(0).text()).isEqualTo("{\"deviceCode\":\"dc-1\"}");
    }

    @Test
    @DisplayName("a 500 maps to TRANSIENT")
    void serverErrorIsTransient() {
        responder.set(answer(500, error("internal_error", "boom")));

        assertThat(client.pollToken("dc-1").kind()).isEqualTo(DeviceAuthorizationClient.PollResult.Kind.TRANSIENT);
    }

    @Test
    @DisplayName("a refused connection maps to TRANSIENT")
    void refusedConnectionIsTransient() {
        int port = other.getAddress().getPort();
        other.stop(0);
        HttpRequestUtils.setBaseUrlForTesting("http://127.0.0.1:" + port);

        assertThat(client.pollToken("dc-1").kind()).isEqualTo(DeviceAuthorizationClient.PollResult.Kind.TRANSIENT);
    }

    @Test
    @DisplayName("a 200 that is not JSON maps to TRANSIENT")
    void unreadableGrantIsTransient() {
        responder.set(answer(200, "<html>proxy page</html>"));

        assertThat(client.pollToken("dc-1").kind()).isEqualTo(DeviceAuthorizationClient.PollResult.Kind.TRANSIENT);
    }

    @Test
    @DisplayName("a 400 that is not JSON maps to TRANSIENT")
    void unreadableErrorIsTransient() {
        responder.set(answer(400, "Bad Request"));

        assertThat(client.pollToken("dc-1").kind()).isEqualTo(DeviceAuthorizationClient.PollResult.Kind.TRANSIENT);
    }

    @Test
    @DisplayName("a 200 whose credential lacks ucs_ is TRANSIENT and the WARNING names the field, not the value")
    void malformedCredentialIsTransientAndNotLogged() {
        responder.set(answer(200, "{\"data\":{\"credential\":\"not-a-server-secret-77\",\"credentialId\":\"c\","
                + "\"serverId\":\"01JTEST\",\"userId\":43,\"serverName\":\"S\"}}"));

        DeviceAuthorizationClient.PollResult result = client.pollToken("dc-1");

        assertThat(result.kind()).isEqualTo(DeviceAuthorizationClient.PollResult.Kind.TRANSIENT);
        assertThat(logLines).anyMatch(line -> line.startsWith(Level.WARNING + " ") && line.contains("credential"));
        assertThat(logLines).noneMatch(line -> line.contains("not-a-server-secret-77"));
    }

    @Test
    @DisplayName("a 200 without a serverId is TRANSIENT and the WARNING names serverId")
    void grantWithoutServerIdIsTransient() {
        responder.set(answer(200, "{\"data\":{\"credential\":\"" + SECRET + "\"}}"));

        assertThat(client.pollToken("dc-1").kind()).isEqualTo(DeviceAuthorizationClient.PollResult.Kind.TRANSIENT);
        assertThat(logLines).anyMatch(line -> line.startsWith(Level.WARNING + " ") && line.contains("serverId"));
        assertThat(logLines).noneMatch(line -> line.contains(SECRET));
    }

    @Test
    @DisplayName("a valid 200 is GRANTED and its toString carries no secret")
    void validGrantIsGranted() {
        responder.set(answer(200, "{\"data\":{\"credential\":\"" + SECRET + "\",\"credentialId\":\"cred-1\","
                + "\"serverId\":\"01JTEST\",\"userId\":43,\"serverName\":\"Survival\"}}"));

        DeviceAuthorizationClient.PollResult result = client.pollToken("dc-1");

        assertThat(result.kind()).isEqualTo(DeviceAuthorizationClient.PollResult.Kind.GRANTED);
        assertThat(result.credential().bearerValue()).isEqualTo(SECRET);
        assertThat(result.credential().getServerName()).isEqualTo("Survival");
        assertThat(result.toString()).doesNotContain(SECRET);
        assertThat(result.credential().toString()).doesNotContain(SECRET);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertThat((Object) result.credential().getUserId()).isEqualTo(43L),
                () -> assertThat(result.credential().toDocument().get("userId")).isEqualTo(43L),
                () -> assertThat(result.credential().toDocument()).containsOnlyKeys(
                        "credential", "credentialId", "serverId", "userId", "serverName", "linkedAt"),
                () -> assertThat(JsonParser.parseString(new com.google.gson.Gson()
                        .toJson(result.credential().toDocument())).getAsJsonObject().keySet())
                        .containsExactlyInAnyOrder("credential", "credentialId", "serverId", "userId",
                                "serverName", "linkedAt"));
    }

    private static String grantWithUserId(String json) {
        String owner = "missing".equals(json) ? "" : ",\"userId\":" + json;
        return "{\"data\":{\"credential\":\"" + SECRET + "\",\"credentialId\":\"cred-1\","
                + "\"serverId\":\"01JTEST\",\"serverName\":\"Survival\"" + owner + "}}";
    }

    @ParameterizedTest(name = "userId {0} is an exact safe-positive integer")
    @ValueSource(strings = {"1", "43", "9007199254740991", "43.0", "4.3e1"})
    void numericUserIdIsPreserved(String json) {
        responder.set(answer(200, grantWithUserId(json)));

        DeviceAuthorizationClient.PollResult result = client.pollToken("dc-1");

        long expected = JsonParser.parseString(json).getAsBigDecimal().longValueExact();
        assertThat(result.kind()).isEqualTo(DeviceAuthorizationClient.PollResult.Kind.GRANTED);
        assertThat(result.credential().getUserId()).isEqualTo(expected);
        assertThat(result.credential().toDocument()).containsEntry("userId", expected);
        assertThat(logLines).isEmpty();
    }

    @ParameterizedTest(name = "invalid userId #{index} is transient without logging its value")
    @ValueSource(strings = {"missing", "null", "\"43\"", "\"user-value-sentinel\"", "true", "{}",
            "{\"owner\":\"user-value-sentinel\"}", "[43]", "43.5", "4.3000000000000001e1", "0", "-1",
            "9007199254740992", "9007199254740991.1", "18446744073709551659", "1e100", "-1e100",
            "1e-100", "1e9999999999"})
    void invalidUserIdIsTransientAndOnlyItsDefectIsLogged(String json) {
        responder.set(answer(200, grantWithUserId(json)));

        DeviceAuthorizationClient.PollResult result = client.pollToken("device-code-sentinel");

        assertThat(result.kind()).isEqualTo(DeviceAuthorizationClient.PollResult.Kind.TRANSIENT);
        assertThat(result.credential()).isNull();
        assertThat(result.retryAfterSeconds()).isEqualTo(-1);
        assertThat(logLines).containsExactly("WARNING UltiCloud: ignored an unusable device-login grant "
                + "(the userId field is missing or is not a valid positive safe integer); polling continues.");
        assertThat(logRecords).singleElement().satisfies(record -> {
            assertThat(record.getThrown()).isNull();
            assertThat(record.getParameters()).isNull();
        });
    }

    @Test
    @DisplayName("a 302 is not followed: the host named by Location sees nothing")
    void redirectIsNotFollowed() {
        responder.set(exchange -> {
            exchange.getResponseHeaders().set("Location", baseUrl(other) + "/v1/device/token");
            send(exchange, 302, "{}");
        });

        DeviceAuthorizationClient.PollResult result = client.pollToken("dc-1");

        assertThat(otherRequests).isEmpty();
        assertThat(cloudRequests).hasSize(1);
        assertThat(result.kind()).isNotEqualTo(DeviceAuthorizationClient.PollResult.Kind.GRANTED);
    }

    @Test
    @DisplayName("a null-body revoke returns a 302 without forwarding its bearer")
    void emptyPostRedirectIsNotFollowed() {
        responder.set(exchange -> {
            exchange.getResponseHeaders().set("Location", baseUrl(other) + "/v1/agent/credential/revoke");
            send(exchange, 302, "{}");
        });

        CloudHttp.Response response = CloudHttp.postJson("/v1/agent/credential/revoke", null, SECRET);

        assertThat(response.outcome()).isEqualTo(CloudHttp.Outcome.OK);
        assertThat(response.status()).isEqualTo(302);
        assertThat(cloudRequests).hasSize(1);
        assertThat(otherRequests).isEmpty();
        assertThat(response.toString()).doesNotContain(SECRET);
    }

    // ---- 429 and Retry-After ----

    @Test
    @DisplayName("a token poll answered 429 with Retry-After: 7 is TRANSIENT with retryAfterSeconds 7")
    void tokenPoll429CarriesRetryAfter() {
        responder.set(answerWithRetryAfter(429, "7", error("rate_limited", "slow")));

        DeviceAuthorizationClient.PollResult result = client.pollToken("dc-1");

        assertThat(result.kind()).isEqualTo(DeviceAuthorizationClient.PollResult.Kind.TRANSIENT);
        assertThat(result.retryAfterSeconds()).isEqualTo(7);
    }

    @Test
    @DisplayName("Retry-After as an HTTP date is ignored")
    void retryAfterHttpDateIsIgnored() {
        responder.set(answerWithRetryAfter(429, "Wed, 21 Oct 2026 07:28:00 GMT", error("rate_limited", "slow")));

        assertThat(client.pollToken("dc-1").retryAfterSeconds()).isEqualTo(-1);
    }

    @Test
    @DisplayName("Retry-After: -1 is ignored")
    void negativeRetryAfterIsIgnored() {
        responder.set(answerWithRetryAfter(429, "-1", error("rate_limited", "slow")));

        assertThat(client.pollToken("dc-1").retryAfterSeconds()).isEqualTo(-1);
    }

    @Test
    @DisplayName("Retry-After above an hour is clamped to 3600")
    void hugeRetryAfterIsClamped() {
        assertThat(CloudHttp.parseRetryAfter("999999999999999")).isEqualTo(3600);
        assertThat(CloudHttp.parseRetryAfter(" 12 ")).isEqualTo(12);
        assertThat(CloudHttp.parseRetryAfter(null)).isEqualTo(-1);
    }

    @Test
    @DisplayName("an authorize answered 429 with Retry-After: 30 names the 30 seconds")
    void authorize429NamesTheWait() {
        responder.set(answerWithRetryAfter(429, "30", error("rate_limited", "slow")));

        DeviceAuthorizationClient.AuthorizeResult result = client.authorize("6.3.1", null, null);

        assertThat(result.isGranted()).isFalse();
        assertThat(result.failureReason())
                .isEqualTo("UltiCloud is limiting device-code requests; try again in 30 seconds");
        assertThat(result.retryAfterSeconds()).isEqualTo(30);
    }

    @ParameterizedTest(name = "JSON retryAfterSeconds {0} -> {1}")
    @CsvSource({"1, 1", "3600, 3600", "3601, 3601", "86400, 86400", "1.0, 1", "8.64e4, 86400",
            "0, -1", "-1, -1", "86401, -1", "1.5, -1", "\"43\", -1", "true, -1", "null, -1",
            "{}, -1", "[], -1", "1e100, -1", "1e9999999999, -1", "18446744073709551617, -1"})
    void authorizeJsonRetryAfterUsesExactContractRange(String json, long expected) {
        responder.set(answer(429, "{\"error\":{\"code\":\"rate_limited\",\"message\":\"slow\","
                + "\"details\":{\"retryAfterSeconds\":" + json + "}}}"));

        DeviceAuthorizationClient.AuthorizeResult result = client.authorize("6.3.1", null, null);

        assertThat(result.isGranted()).isFalse();
        assertThat(result.retryAfterSeconds()).isEqualTo(expected);
        assertThat(result.failureReason()).isEqualTo(expected < 0
                ? "UltiCloud is limiting device-code requests; try again later"
                : "UltiCloud is limiting device-code requests; try again in " + expected + " seconds");
    }

    @Test
    void authorizeRetryAfterHeaderStillTakesPrecedenceAndKeepsItsOwnRange() {
        responder.set(answerWithRetryAfter(429, "99999", "{\"error\":{\"details\":{\"retryAfterSeconds\":86400}}}"));

        assertThat(client.authorize("6.3.1", null, null).retryAfterSeconds()).isEqualTo(3600);
    }

    @ParameterizedTest(name = "authorize timing {0}/{1} uses constants or their identical fallback")
    @CsvSource({"600, 5", "600.0, 5e0", "6e2, 5.0", "601.9, 6.9", "600.9, 5.9", "1, 1",
            "18446744073709552217, 18446744073709551622", "1e100, 1e100", "1e9999999999, 1e9999999999",
            "\"601\", \"6\"", "null, null", "{}, []", "true, false", "0, -1"})
    void authorizeTimingNeverTruncatesOrWraps(String expires, String interval) {
        responder.set(answer(200, authorizationBody(",\"expiresIn\":" + expires + ",\"interval\":" + interval)));

        DeviceAuthorizationClient.AuthorizeResult result = client.authorize("6.3.1", null, null);

        assertThat(result.isGranted()).isTrue();
        assertThat(result.grant().expiresInSeconds()).isEqualTo(600L);
        assertThat(result.grant().intervalSeconds()).isEqualTo(5L);
    }

    @Test
    void absentAuthorizeTimingKeepsTheExistingDefaults() {
        responder.set(answer(200, authorizationBody("")));

        DeviceAuthorizationClient.AuthorizeResult result = client.authorize("6.3.1", null, null);

        assertThat(result.isGranted()).isTrue();
        assertThat(result.grant().expiresInSeconds()).isEqualTo(600L);
        assertThat(result.grant().intervalSeconds()).isEqualTo(5L);
    }

    private static String authorizationBody(String timing) {
        return "{\"data\":{\"deviceCode\":\"dc-1\",\"userCode\":\"BCDF-GHJK\","
                + "\"verificationUri\":\"https://panel.ultikits.com/device\"" + timing + "}}";
    }

    @Test
    @DisplayName("an authorize rejected with an error code reports the status and the code")
    void authorizeRejectionNamesStatusAndCode() {
        StringBuilder longMessage = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            longMessage.append("too long ");
        }
        responder.set(answer(400, error("invalid_request", longMessage.toString())));

        DeviceAuthorizationClient.AuthorizeResult result = client.authorize("6.3.1", null, null);

        assertThat(result.failureReason()).startsWith("UltiCloud answered HTTP 400 (invalid_request)");
        assertThat(result.failureReason().length()).isLessThanOrEqualTo(260);
    }

    // ---- request bodies ----

    @Test
    @DisplayName("the authorize body carries the D-03 fields and omits the optional ones when null")
    void authorizeBodyOmitsNullOptionals() {
        responder.set(answer(500, "{}"));

        client.authorize("6.3.1-SNAPSHOT", null, null);

        JsonObject body = JsonParser.parseString(cloudRequests.get(0).text()).getAsJsonObject();
        assertThat(body.get("clientKind").getAsString()).isEqualTo("server");
        assertThat(body.get("framework").getAsString()).isEqualTo("6.3.1-SNAPSHOT");
        assertThat(body.get("protocol").getAsInt()).isEqualTo(2);
        assertThat(body.has("suggestedName")).isFalse();
        assertThat(body.has("legacyServerUuid")).isFalse();
    }

    @Test
    @DisplayName("a stored uuid the rule rejects sends no legacyServerUuid key at all")
    void rejectedStoredUuidSendsNoAttachKey() throws IOException {
        Files.write(tempDir.resolve("credentials.json"), "{\"uuid\":\"0123456789abc\"}".getBytes(StandardCharsets.UTF_8));
        responder.set(answer(500, "{}"));

        client.authorize("6.3.1", null, CloudAuthManager.storedLegacyServerUuid());

        JsonObject body = JsonParser.parseString(cloudRequests.get(0).text()).getAsJsonObject();
        assertThat(body.has("legacyServerUuid")).isFalse();
    }

    @Test
    @DisplayName("a stored hyphenated uuid is sent as 32 hex digits")
    void hyphenatedStoredUuidIsNormalised() throws IOException {
        Files.write(tempDir.resolve("credentials.json"),
                "{\"uuid\":\"01234567-89AB-cdef-0123-456789abcdef\"}".getBytes(StandardCharsets.UTF_8));
        responder.set(answer(500, "{}"));

        client.authorize("6.3.1", null, CloudAuthManager.storedLegacyServerUuid());

        JsonObject body = JsonParser.parseString(cloudRequests.get(0).text()).getAsJsonObject();
        assertThat(body.has("legacyServerUuid")).as("the authorize body names the install id legacyServerUuid").isTrue();
        assertThat(body.get("legacyServerUuid").getAsString()).isEqualTo("0123456789abcdef0123456789abcdef");
        // attachToServerId is the panel's field on /v1/device/approve, never the plugin's.
        assertThat(body.has("attachToServerId")).isFalse();
    }

    @Test
    @DisplayName("postJson with a null body sends Content-Length: 0 without a JSON Content-Type")
    // A JSON Content-Type on an empty body would make a strict backend try to parse "" (contract
    // answer 14: the revoke call has no body).
    void emptyPostSendsZeroContentLengthWithoutJsonContentType() {
        responder.set(answer(200, "{\"data\":{\"revoked\":true}}"));

        CloudHttp.Response response = CloudHttp.postJson("/v1/agent/credential/revoke", null, "ucs_x");

        assertThat(response.outcome()).isEqualTo(CloudHttp.Outcome.OK);
        assertThat(response.status()).isEqualTo(200);
        assertThat(cloudRequests).hasSize(1);
        assertThat(cloudRequests.get(0).method).isEqualTo("POST");
        assertThat(cloudRequests.get(0).body).isEmpty();
        // Streaming prevents a hidden retry. The JDK may add its default form media type, but an
        // empty revoke must not claim to be JSON and make the backend parse an empty JSON document.
        assertThat(cloudRequests.get(0).contentType).isIn(null, "application/x-www-form-urlencoded");
        assertThat(cloudRequests.get(0).contentLength).isEqualTo("0");
        System.out.println("CloudHttp empty POST accepted: Content-Length=" + cloudRequests.get(0).contentLength
                + ", bodyBytes=" + cloudRequests.get(0).body.length
                + ", Content-Type=" + cloudRequests.get(0).contentType + ", status=" + response.status());
        assertThat(response.toString()).doesNotContain("ucs_x");
    }

    @Test
    @DisplayName("a blank base URL makes no request")
    void blankBaseUrlIsNotConfigured() {
        HttpRequestUtils.setBaseUrlForTesting("  ");

        CloudHttp.Response response = CloudHttp.postJson("/v1/device/token", new JsonObject(), null);

        assertThat(response.outcome()).isEqualTo(CloudHttp.Outcome.NOT_CONFIGURED);
        assertThat(cloudRequests).isEmpty();
    }
}
