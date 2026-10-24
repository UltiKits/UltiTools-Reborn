package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.logging.Level;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests of the v2 agent connection over real loopback sockets: a raw stub that
 * answers the upgrade in the gateway's documented order (contract answer 8: either protocol
 * header missing or an unsupported value, then the framework minimum -> 426; then the credential
 * -> 401; then the plan limit -> 402), and Java-WebSocket servers that accept and then close.
 * Every delay is injected in milliseconds, so no case sleeps for seconds.
 */
@DisplayName("AgentConnection over loopback sockets")
class AgentConnectionLoopbackTest {

    private static final String GOOD_BEARER = "ucs_GOOD";
    private static final long OBSERVE_MS = 600;
    private static final long WAIT_MS = 5000;

    private ScheduledExecutorService scheduler;
    private RawGateway raw;
    private WebSocketServer ws;
    private AgentConnection connection;
    private final Recorder recorder = new Recorder();
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> bearer = new AtomicReference<>(GOOD_BEARER);

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.stop();
        }
        scheduler.shutdownNow();
        if (raw != null) {
            raw.close();
        }
        if (ws != null) {
            ws.stop(500);
        }
    }

    // ---------------------------------------------------------------- fixtures

    /** A policy whose normal and long delays are 20..40 ms. */
    static ReconnectPolicy fastPolicy() {
        return new ReconnectPolicy(() -> 0.5, 20, 40, 20, 40);
    }

    static AgentConnection.Timing fastTiming(long handshakeDeadlineMs, long heartbeatIntervalMs,
                                             long heartbeatTimeoutMs, long planLimitRetryMs) {
        return AgentConnection.Timing.of(handshakeDeadlineMs, heartbeatIntervalMs, heartbeatTimeoutMs,
                600000L, planLimitRetryMs);
    }

    private AgentConnection connect(URI endpoint, String framework, AgentConnection.Timing timing) {
        connection = new AgentConnection(endpoint, bearer::get, framework, scheduler, recorder,
                AgentConnection.realSockets(2000, this::log), fastPolicy(), System::currentTimeMillis,
                timing, this::log);
        return connection;
    }

    private AgentConnection connect(URI endpoint, String framework) {
        return connect(endpoint, framework, fastTiming(5000, 60000, 120000, 400));
    }

    private void log(Level level, String message) {
        logs.add(level.getName() + " " + message);
    }

    private static URI endpointFor(int port) {
        return AgentEndpoint.fromApiUrl("http://127.0.0.1:" + port);
    }

    static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + WAIT_MS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            Thread.sleep(5);
        }
    }

    /** Records every listener callback, in order. */
    static final class Recorder implements AgentConnection.Listener {
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<AgentConnection.State> states = new CopyOnWriteArrayList<>();
        final List<UpgradeRejection> planNotices = new CopyOnWriteArrayList<>();
        volatile Runnable onOpenedHook;

        @Override
        public void onStateChanged(AgentConnection.State state) {
            states.add(state);
        }

        @Override
        public void onCredentialRejected(int statusOrCloseCode, String requestIdOrNull) {
            events.add("credentialRejected " + statusOrCloseCode + " " + requestIdOrNull);
        }

        @Override
        public void onUpgradeRequired(String minFrameworkOrNull, String requestIdOrNull) {
            events.add("upgradeRequired " + minFrameworkOrNull + " " + requestIdOrNull);
        }

        @Override
        public void onSuperseded() {
            events.add("superseded");
        }

        @Override
        public void onPlanLimited(UpgradeRejection notice) {
            planNotices.add(notice);
            events.add("planLimited");
        }

        @Override
        public void onOpened() {
            events.add("opened");
            Runnable hook = onOpenedHook;
            if (hook != null) {
                hook.run();
            }
        }

        @Override
        public void onText(String text) {
            events.add("text " + text);
        }

        long count(String prefix) {
            return events.stream().filter(e -> e.startsWith(prefix)).count();
        }
    }

    /** One response, written as one or more chunks with a pause between them. */
    static final class Reply {
        final List<byte[]> chunks = new ArrayList<>();
        long pauseMs = 50;
        boolean silent;

        static Reply of(String status, String headers, String body) {
            Reply r = new Reply();
            byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + status + "\r\n" + headers
                    + (body == null ? "" : "Content-Type: application/json\r\n"
                    + "Content-Length: " + bodyBytes.length + "\r\n") + "\r\n";
            byte[] headBytes = head.getBytes(StandardCharsets.ISO_8859_1);
            byte[] all = new byte[headBytes.length + bodyBytes.length];
            System.arraycopy(headBytes, 0, all, 0, headBytes.length);
            System.arraycopy(bodyBytes, 0, all, headBytes.length, bodyBytes.length);
            r.chunks.add(all);
            return r;
        }

        static Reply silent() {
            Reply r = new Reply();
            r.silent = true;
            return r;
        }

        /** Splits the single chunk at {@code offset} into two writes. */
        Reply splitAt(int offset) {
            byte[] all = chunks.remove(0);
            byte[] a = new byte[offset];
            byte[] b = new byte[all.length - offset];
            System.arraycopy(all, 0, a, 0, offset);
            System.arraycopy(all, offset, b, 0, b.length);
            chunks.add(a);
            chunks.add(b);
            return this;
        }

        /** Splits the single chunk right after its head. */
        Reply splitAfterHead() {
            String text = new String(chunks.get(0), StandardCharsets.ISO_8859_1);
            return splitAt(text.indexOf("\r\n\r\n") + 4);
        }
    }

    /** The parsed request head of one connection. */
    static final class Request {
        final String requestLine;
        final List<String[]> headers = new ArrayList<>();

        Request(String head) {
            String[] lines = head.split("\r\n");
            requestLine = lines[0];
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    headers.add(new String[]{lines[i].substring(0, colon).trim(),
                            lines[i].substring(colon + 1).trim()});
                }
            }
        }

        List<String> values(String name) {
            List<String> out = new ArrayList<>();
            for (String[] h : headers) {
                if (h[0].equalsIgnoreCase(name)) {
                    out.add(h[1]);
                }
            }
            return out;
        }

        String value(String name) {
            List<String> v = values(name);
            return v.isEmpty() ? null : v.get(0);
        }
    }

    /**
     * A raw HTTP stub on 127.0.0.1 that records each request head and answers with the reply
     * its responder chooses, then closes.
     */
    static final class RawGateway implements AutoCloseable {
        final ServerSocket server;
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final List<Long> acceptedAt = new CopyOnWriteArrayList<>();
        final List<Socket> open = new CopyOnWriteArrayList<>();
        volatile Function<Request, Reply> responder;
        private final Thread thread;

        RawGateway(Function<Request, Reply> responder) throws IOException {
            this.responder = responder;
            this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            this.thread = new Thread(this::run, "raw-gateway");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void run() {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    acceptedAt.add(System.currentTimeMillis());
                    Thread t = new Thread(() -> serve(s), "raw-gateway-conn");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void serve(Socket s) {
            open.add(s);
            try {
                s.setSoTimeout(3000);
                String head = readHead(s.getInputStream());
                if (head == null) {
                    return;
                }
                Request request = new Request(head);
                requests.add(request);
                Reply reply = responder.apply(request);
                if (reply.silent) {
                    // Accept TCP, read the request, never answer; keep the socket until closed.
                    InputStream in = s.getInputStream();
                    s.setSoTimeout(0);
                    while (in.read() >= 0) {
                        // drain until the client goes away
                    }
                    return;
                }
                OutputStream out = s.getOutputStream();
                for (int i = 0; i < reply.chunks.size(); i++) {
                    if (i > 0) {
                        Thread.sleep(reply.pauseMs);
                    }
                    out.write(reply.chunks.get(i));
                    out.flush();
                }
                Thread.sleep(50);
            } catch (IOException | InterruptedException e) {
                // the client went away; nothing to record
            } finally {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closing a socket the client already closed
                }
            }
        }

        static String readHead(InputStream in) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int matched = 0;
            byte[] end = {'\r', '\n', '\r', '\n'};
            try {
                int b;
                while ((b = in.read()) >= 0) {
                    buf.write(b);
                    matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
                    if (matched == 4) {
                        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
                    }
                }
            } catch (SocketTimeoutException e) {
                return null;
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket s : open) {
                s.close();
            }
        }
    }

    static final String BODY_426 = "{\"error\":{\"code\":\"upgrade_required\",\"message\":\"m\","
            + "\"requestId\":\"req-426\",\"details\":{\"supportedProtocols\":[2],\"minFramework\":\"6.3.1\"}}}";
    static final String BODY_401 = "{\"error\":{\"code\":\"invalid_credential\",\"message\":\"m\","
            + "\"requestId\":\"req-401\"}}";
    /** The contract 2.0.0 shape: the plan-limit fields live in {@code error.details}. */
    static final String BODY_402 = "{\"error\":{\"code\":\"plan_limit\",\"message\":\"m\","
            + "\"requestId\":\"req-402\",\"details\":{\"reason\":\"pro_expired_over_limit\","
            + "\"graceEndedAt\":\"2026-10-20T00:00:00.000Z\",\"manageUrl\":\"https://panel.ultikits.com/billing\"}}}";
    static final String BODY_429 = "{\"error\":{\"code\":\"rate_limited\",\"message\":\"m\","
            + "\"requestId\":\"req-429\"}}";

    static Reply reply426() {
        return Reply.of("426 Upgrade Required",
                "X-UltiCloud-Protocol-Supported: 2\r\nX-UltiCloud-Min-Framework: 6.3.1\r\n", BODY_426);
    }

    static Reply reply401() {
        return Reply.of("401 Unauthorized", "", BODY_401);
    }

    /**
     * The gateway's documented order (contract answer 8, protocol decisions section i): protocol
     * header, framework header and minimum, credential, then the plan limit.
     */
    static Function<Request, Reply> gatewayOrder(String minFramework, boolean planLimited) {
        return request -> {
            String protocol = request.value("X-UltiCloud-Protocol");
            String framework = request.value("X-UltiCloud-Framework");
            if (protocol == null || framework == null || !"2".equals(protocol)) {
                return reply426();
            }
            if (minFramework != null && compareVersions(framework, minFramework) < 0) {
                return reply426();
            }
            String auth = request.value("Authorization");
            if (!("Bearer " + GOOD_BEARER).equals(auth)) {
                return reply401();
            }
            if (planLimited) {
                return Reply.of("402 Payment Required", "", BODY_402);
            }
            return reply401();
        };
    }

    static int compareVersions(String a, String b) {
        int[] x = numbers(a);
        int[] y = numbers(b);
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) {
                return Integer.compare(x[i], y[i]);
            }
        }
        return 0;
    }

    private static int[] numbers(String v) {
        int[] out = new int[3];
        String[] parts = v.split("[^0-9]+");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            out[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
        }
        return out;
    }

    /** Sends a hand-written upgrade request and returns the response status line. */
    static String rawRequest(int port, String... headers) throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(3000);
            StringBuilder sb = new StringBuilder("GET /v1/agent HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n");
            for (String h : headers) {
                sb.append(h).append("\r\n");
            }
            sb.append("\r\n");
            s.getOutputStream().write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            String head = RawGateway.readHead(s.getInputStream());
            return head == null ? null : head.split("\r\n")[0];
        }
    }

    /** A Java-WebSocket server on 127.0.0.1 whose behaviour is given per connection. */
    static class LoopbackServer extends WebSocketServer {
        final CountDownLatch started = new CountDownLatch(1);
        final List<String> authorizations = new CopyOnWriteArrayList<>();
        final List<Long> openedAt = new CopyOnWriteArrayList<>();
        final List<String> received = new CopyOnWriteArrayList<>();

        LoopbackServer() {
            super(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            setReuseAddr(true);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            authorizations.add(handshake.getFieldValue("Authorization"));
            openedAt.add(System.currentTimeMillis());
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            // nothing to record
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            received.add(message);
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            // a client going away mid-test
        }

        @Override
        public void onStart() {
            started.countDown();
        }

        int connections() {
            return openedAt.size();
        }
    }

    private LoopbackServer startServer(LoopbackServer server) throws InterruptedException {
        ws = server;
        server.start();
        assertThat(server.started.await(5, TimeUnit.SECONDS)).isTrue();
        return server;
    }

    // ---------------------------------------------------------------- cases

    @Test
    @DisplayName("A: 401 at the upgrade ends in CREDENTIAL_REJECTED after one connection carrying all three headers")
    void refusedWith401() throws Exception {
        raw = new RawGateway(gatewayOrder(null, false));
        bearer.set("ucs_BAD");
        connect(endpointFor(raw.port()), "6.3.1-TEST").start();

        await(() -> connection.state() == AgentConnection.State.CREDENTIAL_REJECTED, "CREDENTIAL_REJECTED");
        Thread.sleep(OBSERVE_MS);

        assertThat(raw.requests).hasSize(1);
        Request request = raw.requests.get(0);
        assertThat(request.requestLine).startsWith("GET /v1/agent ");
        assertThat(request.values("Authorization")).containsExactly("Bearer ucs_BAD");
        assertThat(request.values("X-UltiCloud-Protocol")).containsExactly("2");
        assertThat(request.values("X-UltiCloud-Framework")).containsExactly("6.3.1-TEST");
        assertThat(recorder.events).containsExactly("credentialRejected 401 req-401");
        assertThat(connection.state()).isEqualTo(AgentConnection.State.CREDENTIAL_REJECTED);
        assertThat(logs).noneMatch(line -> line.contains("ucs_BAD"));
    }

    @Test
    @DisplayName("Controls: the same stub answers 426 when either protocol header is missing")
    void stubAnswers426WithoutEitherHeader() throws Exception {
        raw = new RawGateway(gatewayOrder(null, false));
        assertThat(rawRequest(raw.port(), "Authorization: Bearer ucs_BAD", "X-UltiCloud-Framework: 6.3.1"))
                .startsWith("HTTP/1.1 426");
        assertThat(rawRequest(raw.port(), "Authorization: Bearer ucs_BAD", "X-UltiCloud-Protocol: 2"))
                .startsWith("HTTP/1.1 426");
        assertThat(rawRequest(raw.port(), "Authorization: Bearer ucs_BAD", "X-UltiCloud-Protocol: 2",
                "X-UltiCloud-Framework: 6.3.1")).startsWith("HTTP/1.1 401");
    }

    @Test
    @DisplayName("B: 426 at the upgrade ends in UPGRADE_REQUIRED with the body's minimum framework")
    void refusedWith426() throws Exception {
        raw = new RawGateway(gatewayOrder("6.3.1", false));
        connect(endpointFor(raw.port()), "6.3.0").start();

        await(() -> connection.state() == AgentConnection.State.UPGRADE_REQUIRED, "UPGRADE_REQUIRED");
        Thread.sleep(OBSERVE_MS);

        assertThat(raw.requests).hasSize(1);
        assertThat(recorder.events).containsExactly("upgradeRequired 6.3.1 req-426");
        assertThat(recorder.count("credentialRejected")).isZero();
    }

    @Test
    @DisplayName("C: 402 enters PLAN_LIMITED, notifies once and retries only after the plan-limit delay")
    void refusedWith402() throws Exception {
        raw = new RawGateway(gatewayOrder(null, true));
        connect(endpointFor(raw.port()), "6.3.1").start();

        await(() -> connection.state() == AgentConnection.State.PLAN_LIMITED, "PLAN_LIMITED");
        await(() -> raw.requests.size() >= 2, "a second connection");
        Thread.sleep(150);

        assertThat(recorder.planNotices).hasSize(1);
        UpgradeRejection notice = recorder.planNotices.get(0);
        assertThat(notice.planReason()).isEqualTo("pro_expired_over_limit");
        assertThat(notice.graceEndedAt()).isEqualTo("2026-10-20T00:00:00.000Z");
        assertThat(notice.manageUrl()).isEqualTo("https://panel.ultikits.com/billing");
        assertThat(recorder.count("credentialRejected")).isZero();
        long gap = raw.acceptedAt.get(1) - raw.acceptedAt.get(0);
        assertThat(gap).as("gap before the plan-limit retry").isGreaterThanOrEqualTo(350);
        assertThat(connection.state()).isIn(AgentConnection.State.PLAN_LIMITED, AgentConnection.State.CONNECTING);
    }

    @Test
    @DisplayName("D: close 4401 after the upgrade ends in CREDENTIAL_REJECTED")
    void closedWith4401() throws Exception {
        LoopbackServer server = startServer(new LoopbackServer() {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                super.onOpen(conn, handshake);
                conn.close(4401, "revoked");
            }
        });
        connect(endpointFor(server.getPort()), "6.3.1").start();

        await(() -> connection.state() == AgentConnection.State.CREDENTIAL_REJECTED, "CREDENTIAL_REJECTED");
        Thread.sleep(OBSERVE_MS);

        assertThat(server.connections()).isEqualTo(1);
        assertThat(recorder.count("credentialRejected")).isEqualTo(1);
        assertThat(recorder.events).contains("credentialRejected 4401 null");
    }

    @Test
    @DisplayName("E: close 1011 backs off and the next socket carries the bearer read at that moment")
    void closedWith1011ThenRetriedWithFreshBearer() throws Exception {
        LoopbackServer server = startServer(new LoopbackServer() {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                super.onOpen(conn, handshake);
                if (connections() == 1) {
                    bearer.set("ucs_SECOND");
                    conn.close(1011, "internal error");
                }
            }
        });
        bearer.set("ucs_FIRST");
        connect(endpointFor(server.getPort()), "6.3.1").start();

        await(() -> server.connections() >= 2, "a second connection");
        await(() -> connection.state() == AgentConnection.State.OPEN, "OPEN");

        assertThat(recorder.states).contains(AgentConnection.State.BACKOFF);
        assertThat(server.authorizations).containsExactly("Bearer ucs_FIRST", "Bearer ucs_SECOND");
        assertThat(logs).anyMatch(line -> line.matches(
                "INFO UltiCloud: connection attempt 1 failed \\(closed 1011\\); retrying in 1 s\\."));
        assertThat(logs).anyMatch(line -> line.startsWith("INFO UltiCloud: connecting to ws://127.0.0.1:")
                && line.endsWith("/v1/agent (attempt 2)."));
        assertThat(logs).noneMatch(line -> line.toLowerCase(Locale.ROOT).contains("ucs_"));
    }

    // ---------------------------------------------------------------- refusal-capture edges (Task 3)

    @Test
    @DisplayName("a 401 whose head arrives in two writes 50 ms apart is still classified 401")
    void splitHead401() throws Exception {
        raw = new RawGateway(request -> reply401().splitAt(11));
        bearer.set("ucs_BAD");
        connect(endpointFor(raw.port()), "6.3.1").start();
        await(() -> connection.state() == AgentConnection.State.CREDENTIAL_REJECTED, "CREDENTIAL_REJECTED");
        assertThat(recorder.events).containsExactly("credentialRejected 401 req-401");
    }

    @Test
    @DisplayName("a body-less 426 with a hostile minimum-framework header reports no minimum")
    void hostileMinFrameworkHeader() throws Exception {
        for (String value : new String[]{"6.3.1;rm -rf", "1234567890123456789012345678901234567890"}) {
            recorder.events.clear();
            raw = new RawGateway(request -> Reply.of("426 Upgrade Required",
                    "X-UltiCloud-Min-Framework: " + value + "\r\n", null));
            connect(endpointFor(raw.port()), "6.3.1").start();
            await(() -> connection.state() == AgentConnection.State.UPGRADE_REQUIRED, "UPGRADE_REQUIRED");
            assertThat(recorder.events).containsExactly("upgradeRequired null null");
            connection.stop();
            raw.close();
        }
    }

    @Test
    @DisplayName("a 426 whose body arrives after its head is UPGRADE_REQUIRED with the header's minimum")
    void delayedBody426() throws Exception {
        raw = new RawGateway(request -> reply426().splitAfterHead());
        connect(endpointFor(raw.port()), "6.3.1").start();
        await(() -> connection.state() == AgentConnection.State.UPGRADE_REQUIRED, "UPGRADE_REQUIRED");
        assertThat(recorder.events).containsExactly("upgradeRequired 6.3.1 null");
    }

    @Test
    @DisplayName("a 429 with Retry-After 0 backs off without the credential callback")
    void rateLimited429() throws Exception {
        raw = new RawGateway(request -> Reply.of("429 Too Many Requests", "Retry-After: 0\r\n", BODY_429));
        connect(endpointFor(raw.port()), "6.3.1").start();
        await(() -> recorder.states.contains(AgentConnection.State.BACKOFF), "BACKOFF");
        await(() -> logs.stream().anyMatch(l -> l.contains("failed (HTTP 429 rate_limited)")), "the retry line");
        assertThat(recorder.count("credentialRejected")).isZero();
    }

    @Test
    @DisplayName("an accepted upgrade leaves the sink at 101 and a later 1011 is a close, not a refusal")
    void acceptedThenClosedIsAClose() throws Exception {
        LoopbackServer server = startServer(new LoopbackServer());
        List<String> seen = new CopyOnWriteArrayList<>();
        AgentSocket socket = new AgentSocket(endpointFor(server.getPort()),
                new AgentConnection(endpointFor(server.getPort()), bearer::get, "6.3.1", scheduler, recorder)
                        .buildHeaders(GOOD_BEARER), 2000, new AgentConnection.SocketEvents() {
                    @Override
                    public void onOpen() {
                        seen.add("open");
                    }

                    @Override
                    public void onText(String text) {
                        seen.add("text");
                    }

                    @Override
                    public void onUpgradeRefused(int httpStatus, UpgradeRejection rejection) {
                        seen.add("refused " + httpStatus);
                    }

                    @Override
                    public void onClosed(int code, String reason, boolean remote) {
                        seen.add("closed " + code);
                    }
                }, this::log);
        socket.connect();
        await(() -> seen.contains("open"), "open");
        assertThat(socket.lastResponse().status()).isEqualTo(101);
        for (WebSocket conn : server.getConnections()) {
            conn.close(1011, "internal error");
        }
        await(() -> seen.size() == 2, "the close");
        assertThat(seen).containsExactly("open", "closed 1011");
    }

    @Test
    @DisplayName("copyInstance returns a StatusCapturingDraft sharing the sink")
    void copyInstanceSharesTheSink() {
        AtomicReference<UpgradeRejection> sink = new AtomicReference<>();
        StatusCapturingDraft draft = new StatusCapturingDraft(sink);
        assertThat(draft.copyInstance()).isInstanceOf(StatusCapturingDraft.class)
                .isNotSameAs(draft)
                .satisfies(copy -> assertThat(((StatusCapturingDraft) copy).sink()).isSameAs(sink));
    }

    // ---------------------------------------------------------------- deadline and heartbeat (Task 3)

    @Test
    @DisplayName("a server that never answers the upgrade is closed at the handshake deadline and retried")
    void handshakeDeadline() throws Exception {
        raw = new RawGateway(request -> Reply.silent());
        connect(endpointFor(raw.port()), "6.3.1", fastTiming(300, 60000, 120000, 400)).start();
        await(() -> raw.requests.size() == 1, "the request");
        CountDownLatch probe = new CountDownLatch(1);
        long submitted = System.currentTimeMillis();
        scheduler.execute(probe::countDown);
        assertThat(probe.await(250, TimeUnit.MILLISECONDS)).as("the agent scheduler is not blocked").isTrue();
        assertThat(System.currentTimeMillis() - submitted).isLessThan(250);
        await(() -> recorder.states.contains(AgentConnection.State.BACKOFF), "BACKOFF");
        await(() -> logs.stream().anyMatch(l -> l.contains("failed (no handshake response within 1 s)")),
                "the retry line");
        await(() -> raw.requests.size() >= 2, "a second attempt");
    }

    @Test
    @DisplayName("a server that answers ping with pong keeps one connection across many intervals")
    void heartbeatAnswered() throws Exception {
        LoopbackServer server = startServer(new LoopbackServer() {
            @Override
            public void onMessage(WebSocket conn, String message) {
                super.onMessage(conn, message);
                if ("ping".equals(message)) {
                    conn.send("pong");
                }
            }
        });
        recorder.onOpenedHook = () -> connection.markEstablished();
        connect(endpointFor(server.getPort()), "6.3.1", fastTiming(5000, 50, 200, 400)).start();
        await(() -> server.received.size() >= 8, "eight pings");
        assertThat(server.connections()).isEqualTo(1);
        assertThat(connection.state()).isEqualTo(AgentConnection.State.OPEN);
        assertThat(server.received).containsOnly("ping");
        assertThat(recorder.events).doesNotContain("text pong");
    }

    @Test
    @DisplayName("a server that never answers ping is closed after the heartbeat timeout and reconnected")
    void heartbeatUnanswered() throws Exception {
        LoopbackServer server = startServer(new LoopbackServer());
        recorder.onOpenedHook = () -> connection.markEstablished();
        connect(endpointFor(server.getPort()), "6.3.1", fastTiming(5000, 50, 200, 400)).start();
        await(() -> server.connections() >= 2, "a reconnect");
        assertThat(logs).anyMatch(l -> l.contains("failed (no heartbeat reply within 1 s)"));
    }
}
