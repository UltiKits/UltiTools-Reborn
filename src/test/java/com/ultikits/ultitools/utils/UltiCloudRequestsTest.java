package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.ultikits.ultitools.entities.TokenEntity;

/**
 * Tests for {@link UltiCloudRequests}, the only way a module makes an authenticated UltiCloud
 * request (Phase 18 contract section 12). Every test talks to a real local HTTP server (the JDK's
 * built-in {@code com.sun.net.httpserver}), so the bearer header, the request line and the redirect
 * behaviour are observed on the wire rather than inferred from a mock.
 * <p>
 * Calls run on a worker thread because the helper refuses the server's primary thread; MockBukkit's
 * main thread is the JUnit thread that called {@link MockBukkit#mock()}.
 */
@DisplayName("UltiCloudRequests: authenticated UltiCloud requests without exposing the token")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class UltiCloudRequestsTest {

    /** Synthetic token value -- never a real credential. */
    private static final String TOKEN = "synthetic-access-token-5d1e8f";
    private static final String CREATE_PATH = "/auth/magic-link";
    private static final String POLL_PATH = "/auth/magic-link/poll";

    private HttpServer cloud;
    private HttpServer other;
    private final List<Recorded> cloudRequests = new CopyOnWriteArrayList<>();
    private final List<Recorded> otherRequests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Responder> cloudResponder = new AtomicReference<>();
    private ExecutorService worker;

    /** One request as the local server saw it on the wire. */
    private static final class Recorded {
        final String method;
        final String rawPath;
        final String rawQuery;
        final String authorization;
        final String contentType;
        final String body;

        Recorded(HttpExchange exchange, String body) {
            this.method = exchange.getRequestMethod();
            this.rawPath = exchange.getRequestURI().getRawPath();
            this.rawQuery = exchange.getRequestURI().getRawQuery();
            this.authorization = exchange.getRequestHeaders().getFirst("Authorization");
            this.contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            this.body = body;
        }
    }

    /** How the stub cloud answers a request. */
    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
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

    private static HttpServer startServer(List<Recorded> sink, AtomicReference<Responder> responder)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                sink.add(new Recorded(exchange, readBody(exchange)));
                responder.get().respond(exchange);
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
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        CloudSession.resetForTesting();
        cloudResponder.set(exchange -> send(exchange, 200, "{\"ok\":true}"));
        cloud = startServer(cloudRequests, cloudResponder);
        AtomicReference<Responder> otherResponder = new AtomicReference<>(
            exchange -> send(exchange, 200, "{\"stolen\":true}"));
        other = startServer(otherRequests, otherResponder);
        HttpRequestUtils.setBaseUrlForTesting(baseUrl(cloud));
        worker = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        worker.shutdownNow();
        cloud.stop(0);
        other.stop(0);
        HttpRequestUtils.resetBaseUrl();
        CloudSession.resetForTesting();
        MockBukkitHelper.safeUnmock();
    }

    /** Installs a valid, unexpired token on the current session without touching the disk. */
    private static void connect() throws Exception {
        installToken(TOKEN, System.currentTimeMillis() / 1000L + 3600L);
        assertThat(CloudSession.current().hasValidToken()).isTrue();
    }

    /** Installs {@code accessToken} expiring at {@code expEpochSeconds} on the current session. */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static void installToken(String accessToken, long expEpochSeconds) throws Exception {
        TokenEntity token = new TokenEntity();
        token.setAccess_token(accessToken);
        token.setRefresh_token("synthetic-refresh-token");
        token.setExp(expEpochSeconds);
        Field field = CloudSession.class.getDeclaredField("token");
        field.setAccessible(true);
        field.set(CloudSession.current(), token);
    }

    private UltiCloudRequests.Result offMain(Callable<UltiCloudRequests.Result> call) throws Exception {
        return worker.submit(call).get(20, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Test 1: a connected POST to the create path carries the bearer and returns OK, status and body")
    void connectedPost_attachesBearerAndReturnsTheExchange() throws Exception {
        connect();
        cloudResponder.set(exchange -> send(exchange, 201, "{\"data\":{\"url\":\"u\"}}"));

        UltiCloudRequests.Result result = offMain(() -> UltiCloudRequests.post(CREATE_PATH, "{\"a\":1}"));

        assertThat(result.getOutcome()).isEqualTo(UltiCloudRequests.Outcome.OK);
        assertThat(result.getStatusCode()).isEqualTo(201);
        assertThat(result.getBody()).isEqualTo("{\"data\":{\"url\":\"u\"}}");
        assertThat(cloudRequests).hasSize(1);
        Recorded seen = cloudRequests.get(0);
        assertThat(seen.method).isEqualTo("POST");
        assertThat(seen.rawPath).isEqualTo(CREATE_PATH);
        assertThat(seen.authorization).isEqualTo("Bearer " + TOKEN);
        assertThat(seen.contentType).isEqualTo("application/json; charset=utf-8");
        assertThat(seen.body).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("Test 2: with no valid session the result is NOT_CONNECTED and no request is made")
    void notConnected_makesNoRequest() throws Exception {
        UltiCloudRequests.Result post = offMain(() -> UltiCloudRequests.post(CREATE_PATH, "{}"));
        UltiCloudRequests.Result get = offMain(() -> UltiCloudRequests.get(POLL_PATH,
            Collections.singletonMap("requestId", "r1")));

        assertThat(post.getOutcome()).isEqualTo(UltiCloudRequests.Outcome.NOT_CONNECTED);
        assertThat(get.getOutcome()).isEqualTo(UltiCloudRequests.Outcome.NOT_CONNECTED);
        assertThat(post.getStatusCode()).isEqualTo(-1);
        assertThat(post.getBody()).isNull();
        assertThat(cloudRequests).isEmpty();
    }

    @Test
    @DisplayName("Test 2b: an expired token is NOT_CONNECTED and no request is made")
    void expiredToken_isNotConnected() throws Exception {
        installToken(TOKEN, System.currentTimeMillis() / 1000L - 60L);

        UltiCloudRequests.Result post = offMain(() -> UltiCloudRequests.post(CREATE_PATH, "{}"));
        UltiCloudRequests.Result get = offMain(() -> UltiCloudRequests.get(POLL_PATH, null));

        assertThat(post.getOutcome()).isEqualTo(UltiCloudRequests.Outcome.NOT_CONNECTED);
        assertThat(get.getOutcome()).isEqualTo(UltiCloudRequests.Outcome.NOT_CONNECTED);
        assertThat(cloudRequests).isEmpty();
    }

    @Test
    @DisplayName("Test 2c: an invalidated session still holding a token is NOT_CONNECTED and no request is made")
    void invalidatedSession_isNotConnected() throws Exception {
        connect();
        CloudSession.current().markInvalidatedForTesting();

        UltiCloudRequests.Result post = offMain(() -> UltiCloudRequests.post(CREATE_PATH, "{}"));
        UltiCloudRequests.Result get = offMain(() -> UltiCloudRequests.get(POLL_PATH, null));

        assertThat(post.getOutcome()).isEqualTo(UltiCloudRequests.Outcome.NOT_CONNECTED);
        assertThat(get.getOutcome()).isEqualTo(UltiCloudRequests.Outcome.NOT_CONNECTED);
        assertThat(cloudRequests).isEmpty();
    }

    @Test
    @DisplayName("Test 6b: a token the JDK refuses as a header value (CR/LF) never surfaces; the result is IO_ERROR")
    void tokenRefusedAsAHeaderValue_neverSurfaces() throws Exception {
        String unsafe = "synthetic-crlf-token\r\nX-Injected: yes";
        installToken(unsafe, System.currentTimeMillis() / 1000L + 3600L);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Callable<UltiCloudRequests.Result> post = () -> {
            try {
                return UltiCloudRequests.post(CREATE_PATH, "{}");
            } catch (RuntimeException e) {
                thrown.set(e);
                return null;
            }
        };
        UltiCloudRequests.Result result = offMain(post);

        assertThat(thrown.get())
            .as("exception escaping the helper (its message would carry the bearer)")
            .isNull();
        assertThat(result.getOutcome()).isEqualTo(UltiCloudRequests.Outcome.IO_ERROR);
        assertThat(result.getStatusCode()).isEqualTo(-1);
        assertThat(result.getBody()).isNull();
        assertThat(result.toString()).doesNotContain("synthetic-crlf-token");
        assertThat(cloudRequests).isEmpty();
    }

    @Test
    @DisplayName("Test 3: unlisted paths, traversal, smuggled query and wrong method are PATH_NOT_ALLOWED with no request")
    void pathOutsideTheAllowList_isRefusedWithoutARequest() throws Exception {
        connect();
        Map<String, Callable<UltiCloudRequests.Result>> cases = new LinkedHashMap<>();
        cases.put("POST /oauth/token", () -> UltiCloudRequests.post("/oauth/token", "{}"));
        cases.put("POST traversal", () -> UltiCloudRequests.post("/auth/magic-link/../oauth/token", "{}"));
        cases.put("POST protocol-relative", () -> UltiCloudRequests.post("//evil.example/auth/magic-link", "{}"));
        cases.put("GET smuggled query", () -> UltiCloudRequests.get("/auth/magic-link/poll?x=1", null));
        cases.put("POST percent-encoded", () -> UltiCloudRequests.post("/auth/magic-link%2F..", "{}"));
        cases.put("GET on the create path", () -> UltiCloudRequests.get(CREATE_PATH, null));
        cases.put("POST on the poll path", () -> UltiCloudRequests.post(POLL_PATH, "{}"));
        cases.put("POST trailing slash", () -> UltiCloudRequests.post("/auth/magic-link/", "{}"));
        cases.put("POST backslash", () -> UltiCloudRequests.post("\\auth\\magic-link", "{}"));
        cases.put("POST fragment", () -> UltiCloudRequests.post("/auth/magic-link#x", "{}"));
        cases.put("POST absolute URL", () -> UltiCloudRequests.post("http://evil.example/auth/magic-link", "{}"));
        cases.put("POST null path", () -> UltiCloudRequests.post(null, "{}"));

        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Callable<UltiCloudRequests.Result>> c : cases.entrySet()) {
            UltiCloudRequests.Outcome outcome = offMain(c.getValue()).getOutcome();
            if (outcome != UltiCloudRequests.Outcome.PATH_NOT_ALLOWED) {
                wrong.add(c.getKey() + " -> " + outcome);
            }
        }

        assertThat(wrong).as("cases not refused").isEmpty();
        assertThat(cloudRequests).as("requests that reached the server").isEmpty();
    }

    @Test
    @DisplayName("Test 4: a 302 is returned as-is and the redirect target is never contacted")
    void redirect_isReturnedAndNeverFollowed() throws Exception {
        connect();
        String target = baseUrl(other) + POLL_PATH;
        cloudResponder.set(exchange -> {
            exchange.getResponseHeaders().set("Location", target);
            send(exchange, 302, "");
        });

        UltiCloudRequests.Result result = offMain(() -> UltiCloudRequests.post(CREATE_PATH, "{}"));

        assertThat(result.getOutcome()).isEqualTo(UltiCloudRequests.Outcome.OK);
        assertThat(result.getStatusCode()).isEqualTo(302);
        assertThat(cloudRequests).hasSize(1);
        assertThat(otherRequests).as("requests that reached the redirect target").isEmpty();
    }

    @Test
    @DisplayName("Test 5: a call on the server's primary thread throws IllegalStateException and makes no request")
    void primaryThread_isRefused() throws Exception {
        connect();

        assertThatThrownBy(() -> UltiCloudRequests.post(CREATE_PATH, "{}"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> UltiCloudRequests.get(POLL_PATH, null))
            .isInstanceOf(IllegalStateException.class);
        assertThat(cloudRequests).isEmpty();
    }

    @Test
    @DisplayName("Test 6: after an authenticated exchange, no getter, toString or public member exposes the token")
    void resultAndSurface_neverExposeTheToken() throws Exception {
        connect();
        UltiCloudRequests.Result ok = offMain(() -> UltiCloudRequests.post(CREATE_PATH, "{}"));
        assertThat(ok.getOutcome()).isEqualTo(UltiCloudRequests.Outcome.OK);
        assertThat(cloudRequests).hasSize(1);
        assertThat(cloudRequests.get(0).authorization).isEqualTo("Bearer " + TOKEN);

        // Port 0 can never accept a connection, so the exchange fails at connect time.
        HttpRequestUtils.setBaseUrlForTesting("http://127.0.0.1:0");
        UltiCloudRequests.Result ioError = offMain(() -> UltiCloudRequests.post(CREATE_PATH, "{}"));
        assertThat(ioError.getOutcome()).isEqualTo(UltiCloudRequests.Outcome.IO_ERROR);
        assertThat(ioError.getStatusCode()).isEqualTo(-1);
        assertThat(ioError.getBody()).isNull();

        for (UltiCloudRequests.Result r : new UltiCloudRequests.Result[] {ok, ioError}) {
            assertThat(r.toString()).doesNotContain(TOKEN).doesNotContain("Bearer");
            assertThat(String.valueOf(r.getBody())).doesNotContain(TOKEN);
            assertThat(String.valueOf(r.getOutcome())).doesNotContain(TOKEN);
        }

        List<String> offending = new ArrayList<>();
        for (Class<?> type : new Class<?>[] {UltiCloudRequests.class, UltiCloudRequests.Result.class,
                UltiCloudRequests.Outcome.class}) {
            for (Method m : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers())) {
                    continue;
                }
                List<Type> types = new ArrayList<>();
                types.add(m.getGenericReturnType());
                Collections.addAll(types, m.getGenericParameterTypes());
                for (Type t : types) {
                    if (t.getTypeName().contains(TokenEntity.class.getName())) {
                        offending.add(type.getSimpleName() + "#" + m.getName());
                    }
                }
            }
            for (Field f : type.getDeclaredFields()) {
                if (Modifier.isPublic(f.getModifiers())
                        && f.getGenericType().getTypeName().contains(TokenEntity.class.getName())) {
                    offending.add(type.getSimpleName() + "." + f.getName());
                }
            }
        }
        assertThat(offending).as("public members typed TokenEntity").isEmpty();
    }

    @Test
    @DisplayName("Test 7: GET sends the query map URL-encoded as UTF-8 on the poll path")
    void get_encodesTheQuery() throws Exception {
        connect();

        UltiCloudRequests.Result result = offMain(() -> UltiCloudRequests.get(POLL_PATH,
            Collections.singletonMap("requestId", "a b&c")));

        assertThat(result.getOutcome()).isEqualTo(UltiCloudRequests.Outcome.OK);
        assertThat(cloudRequests).hasSize(1);
        Recorded seen = cloudRequests.get(0);
        assertThat(seen.method).isEqualTo("GET");
        assertThat(seen.rawPath).isEqualTo(POLL_PATH);
        assertThat(seen.rawQuery).isEqualTo("requestId=a+b%26c");
        assertThat(seen.authorization).isEqualTo("Bearer " + TOKEN);
    }
}
