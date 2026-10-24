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
    private final DeviceAuthorizationClient client = new DeviceAuthorizationClient();

    /** One request as the stub saw it on the wire. */
    private static final class Recorded {
        final String path;
        final String contentType;
        final String contentLength;
        final byte[] body;

        Recorded(HttpExchange exchange, byte[] body) {
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
                + "\"serverId\":\"01JTEST\",\"userId\":\"u\",\"serverName\":\"S\"}}"));

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
                + "\"serverId\":\"01JTEST\",\"userId\":\"u-1\",\"serverName\":\"Survival\"}}"));

        DeviceAuthorizationClient.PollResult result = client.pollToken("dc-1");

        assertThat(result.kind()).isEqualTo(DeviceAuthorizationClient.PollResult.Kind.GRANTED);
        assertThat(result.credential().bearerValue()).isEqualTo(SECRET);
        assertThat(result.credential().getServerName()).isEqualTo("Survival");
        assertThat(result.toString()).doesNotContain(SECRET);
        assertThat(result.credential().toString()).doesNotContain(SECRET);
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
        assertThat(body.has("attachToServerId")).isFalse();
    }

    @Test
    @DisplayName("a stored uuid the rule rejects sends no attachToServerId key at all")
    void rejectedStoredUuidSendsNoAttachKey() throws IOException {
        Files.write(tempDir.resolve("credentials.json"), "{\"uuid\":\"0123456789abc\"}".getBytes(StandardCharsets.UTF_8));
        responder.set(answer(500, "{}"));

        client.authorize("6.3.1", null, CloudAuthManager.storedAttachToServerId());

        JsonObject body = JsonParser.parseString(cloudRequests.get(0).text()).getAsJsonObject();
        assertThat(body.has("attachToServerId")).isFalse();
    }

    @Test
    @DisplayName("a stored hyphenated uuid is sent as 32 hex digits")
    void hyphenatedStoredUuidIsNormalised() throws IOException {
        Files.write(tempDir.resolve("credentials.json"),
                "{\"uuid\":\"01234567-89AB-cdef-0123-456789abcdef\"}".getBytes(StandardCharsets.UTF_8));
        responder.set(answer(500, "{}"));

        client.authorize("6.3.1", null, CloudAuthManager.storedAttachToServerId());

        JsonObject body = JsonParser.parseString(cloudRequests.get(0).text()).getAsJsonObject();
        assertThat(body.get("attachToServerId").getAsString()).isEqualTo("0123456789abcdef0123456789abcdef");
    }

    @Test
    @DisplayName("postJson with a null body sends zero body bytes and no Content-Type")
    // A JSON Content-Type on an empty body would make a strict backend try to parse "" (contract
    // answer 14: the revoke call has no body).
    void emptyPostSendsNoBodyAndNoContentType() {
        responder.set(answer(200, "{\"data\":{\"revoked\":true}}"));

        CloudHttp.Response response = CloudHttp.postJson("/v1/agent/credential/revoke", null, "ucs_x");

        assertThat(response.outcome()).isEqualTo(CloudHttp.Outcome.OK);
        assertThat(response.status()).isEqualTo(200);
        assertThat(cloudRequests).hasSize(1);
        assertThat(cloudRequests.get(0).body).isEmpty();
        assertThat(cloudRequests.get(0).contentType).isNull();
        // HttpURLConnection adds a form Content-Type to any request that opens an output stream, so
        // the empty POST opens none and carries no Content-Length either: a request without one has a
        // zero-length body (RFC 9112 section 6.3). A "0" would be equally valid.
        assertThat(cloudRequests.get(0).contentLength).isIn(null, "0");
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
