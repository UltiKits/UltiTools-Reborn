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
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
 * Tests for {@link UltiCloudRequests} after protocol 2.0.0 removed the v1 magic-link exchange: every
 * call is refused without a request, the refusal is announced once per run, and no public member can
 * carry the server credential.
 * <p>
 * A real local HTTP server (the JDK's built-in {@code com.sun.net.httpserver}) is still started for
 * the two former magic-link paths, so "no request is made" is observed on the wire rather than
 * inferred.
 * <p>
 * Calls run on a worker thread because the helper still refuses the server's primary thread;
 * MockBukkit's main thread is the JUnit thread that called {@link MockBukkit#mock()}.
 */
@DisplayName("UltiCloudRequests: every v1 call is refused without a request")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class UltiCloudRequestsTest {

    private static final String CREATE_PATH = "/auth/magic-link";
    private static final String POLL_PATH = "/auth/magic-link/poll";

    private HttpServer cloud;
    private final List<String> cloudRequests = new CopyOnWriteArrayList<>();
    private ExecutorService worker;
    private CapturingHandler logs;

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

    @BeforeEach
    void setUp() throws IOException {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        UltiCloudRequests.resetAnnouncementForTesting();
        cloud = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cloud.createContext("/", exchange -> {
            try {
                cloudRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
                readBody(exchange);
                send(exchange, 200, "{\"ok\":true}");
            } finally {
                exchange.close();
            }
        });
        cloud.start();
        HttpRequestUtils.setBaseUrlForTesting("http://127.0.0.1:" + cloud.getAddress().getPort());
        worker = Executors.newSingleThreadExecutor();
        logs = new CapturingHandler(Logger.getLogger(UltiCloudRequests.class.getName()));
    }

    @AfterEach
    void tearDown() {
        logs.detach();
        worker.shutdownNow();
        cloud.stop(0);
        HttpRequestUtils.resetBaseUrl();
        MockBukkitHelper.safeUnmock();
    }

    private UltiCloudRequests.Result offMain(Callable<UltiCloudRequests.Result> call) throws Exception {
        return worker.submit(call).get(20, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("both former magic-link calls are PATH_NOT_ALLOWED and reach no server")
    void magicLinkCallsAreRefusedWithoutARequest() throws Exception {
        UltiCloudRequests.Result post = offMain(() -> UltiCloudRequests.post(CREATE_PATH, "{\"a\":1}"));
        UltiCloudRequests.Result get = offMain(() -> UltiCloudRequests.get(POLL_PATH,
            Collections.singletonMap("requestId", "r1")));

        for (UltiCloudRequests.Result result : new UltiCloudRequests.Result[] {post, get}) {
            assertThat(result.getOutcome()).isEqualTo(UltiCloudRequests.Outcome.PATH_NOT_ALLOWED);
            assertThat(result.getStatusCode()).isEqualTo(-1);
            assertThat(result.getBody()).isNull();
        }
        assertThat(cloudRequests).as("requests that reached the server").isEmpty();
    }

    @Test
    @DisplayName("every other path is refused the same way, and still reaches no server")
    void otherPathsAreRefused() throws Exception {
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
        cases.put("GET null path", () -> UltiCloudRequests.get(null, null));

        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Callable<UltiCloudRequests.Result>> entry : cases.entrySet()) {
            UltiCloudRequests.Outcome outcome = offMain(entry.getValue()).getOutcome();
            if (outcome != UltiCloudRequests.Outcome.PATH_NOT_ALLOWED) {
                wrong.add(entry.getKey() + " -> " + outcome);
            }
        }

        assertThat(wrong).as("cases not refused").isEmpty();
        assertThat(cloudRequests).as("requests that reached the server").isEmpty();
    }

    @Test
    @DisplayName("the removal is announced once per run, naming CloudPlayerLinks and UltiLogin 2.1.0")
    void removalIsAnnouncedOnce() throws Exception {
        offMain(() -> UltiCloudRequests.post(CREATE_PATH, "{}"));
        offMain(() -> UltiCloudRequests.get(POLL_PATH, null));
        offMain(() -> UltiCloudRequests.post(CREATE_PATH, "{}"));

        assertThat(logs.infoMessages()).as("one INFO line per run").hasSize(1);
        assertThat(logs.infoMessages().get(0)).contains("CloudPlayerLinks").contains("UltiLogin 2.1.0");
    }

    @Test
    @DisplayName("a call on the server's primary thread throws IllegalStateException and reaches no server")
    void primaryThreadIsRefused() {
        assertThatThrownBy(() -> UltiCloudRequests.post(CREATE_PATH, "{}"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> UltiCloudRequests.get(POLL_PATH, null))
            .isInstanceOf(IllegalStateException.class);
        assertThat(cloudRequests).isEmpty();
    }

    @Test
    @DisplayName("no public member of the helper is typed TokenEntity")
    void surfaceNeverExposesTheToken() {
        List<String> offending = new ArrayList<>();
        for (Class<?> type : new Class<?>[] {UltiCloudRequests.class, UltiCloudRequests.Result.class,
                UltiCloudRequests.Outcome.class}) {
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                List<Type> types = new ArrayList<>();
                types.add(method.getGenericReturnType());
                Collections.addAll(types, method.getGenericParameterTypes());
                for (Type generic : types) {
                    if (generic.getTypeName().contains(TokenEntity.class.getName())) {
                        offending.add(type.getSimpleName() + "#" + method.getName());
                    }
                }
            }
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isPublic(field.getModifiers())
                        && field.getGenericType().getTypeName().contains(TokenEntity.class.getName())) {
                    offending.add(type.getSimpleName() + "." + field.getName());
                }
            }
        }
        assertThat(offending).as("public members typed TokenEntity").isEmpty();
    }

    /** Collects this logger's INFO records. */
    private static final class CapturingHandler extends Handler {
        private final Logger logger;
        private final List<String> info = new CopyOnWriteArrayList<>();

        private CapturingHandler(Logger logger) {
            this.logger = logger;
            setLevel(Level.ALL);
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel() == Level.INFO) {
                info.add(String.valueOf(record.getMessage()));
            }
        }

        @Override
        public void flush() {
            // Nothing is buffered.
        }

        @Override
        public void close() {
            // The logger is detached in tearDown.
        }

        private List<String> infoMessages() {
            return info;
        }

        private void detach() {
            logger.removeHandler(this);
        }
    }
}
