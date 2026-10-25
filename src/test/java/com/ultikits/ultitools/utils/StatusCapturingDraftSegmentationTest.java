package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A refused upgrade's body must reach {@link AgentSocket} however the server segments the response
 * (as of 6.3.1, fix round of plan 22-19 for the 22-02 refusal capture).
 * <p>
 * Each case runs the framework's real {@link AgentSocket} (and so {@link StatusCapturingDraft})
 * against a raw loopback server that writes a scripted refusal: the head and the body in separate
 * writes with pauses between them, the body itself in two parts, or everything in one write. The
 * degrading cases (a body over the cap, no {@code Content-Length}, a server that closes before the
 * body, a server that stalls) must report the status alone, and none of them may wait without
 * bound.
 */
@DisplayName("StatusCapturingDraft reads a refusal body regardless of segmentation")
class StatusCapturingDraftSegmentationTest {

    private static final long WAIT_S = 5;
    private static final long PAUSE_MS = 150;

    static final String BODY_402 = "{\"error\":{\"code\":\"plan_limit\",\"message\":\"Plan limit reached.\","
            + "\"requestId\":\"req-402\",\"details\":{\"reason\":\"pro_expired_over_limit\","
            + "\"graceEndedAt\":\"2026-10-20T00:00:00.000Z\",\"manageUrl\":\"https://panel.ultikits.com/billing\"}}}";
    static final String BODY_426 = "{\"error\":{\"code\":\"upgrade_required\",\"message\":\"m\","
            + "\"requestId\":\"req-426\",\"details\":{\"supportedProtocols\":[2],\"minFramework\":\"6.3.1\"}}}";

    private ScriptedServer server;
    private AgentSocket socket;

    @AfterEach
    void tearDown() throws IOException {
        if (socket != null) {
            socket.closeAttempt(AgentConnection.CLOSE_NORMAL, "test over");
        }
        if (server != null) {
            server.close();
        }
    }

    // ---------------------------------------------------------------- the body arrives in full

    @Test
    @DisplayName("a 402 whose body follows its head in a later write carries reason, grace end and manage link")
    void bodyAfterHead() throws Exception {
        byte[][] parts = headAndBody("402 Payment Required", "", BODY_402);
        Outcome outcome = run(Script.of(parts[0]).pause(PAUSE_MS).write(parts[1]).closeAfter());
        assertFull402(outcome);
    }

    @Test
    @DisplayName("a 402 whose body arrives in two parts, the first with the head, is read in full")
    void bodyInTwoPartsFirstWithHead() throws Exception {
        byte[][] parts = headAndBody("402 Payment Required", "", BODY_402);
        byte[][] body = split(parts[1], 40);
        Outcome outcome = run(Script.of(concat(parts[0], body[0])).pause(PAUSE_MS).write(body[1]).closeAfter());
        assertFull402(outcome);
    }

    @Test
    @DisplayName("a 402 written as head, body part one and body part two in three writes is read in full")
    void headThenBodyInTwoParts() throws Exception {
        byte[][] parts = headAndBody("402 Payment Required", "", BODY_402);
        byte[][] body = split(parts[1], parts[1].length / 2);
        Outcome outcome = run(Script.of(parts[0]).pause(PAUSE_MS).write(body[0]).pause(PAUSE_MS)
                .write(body[1]).holdOpen());
        assertFull402(outcome);
    }

    @Test
    @DisplayName("a 426 whose head itself is split inside the header block keeps its headers and body")
    void headSplitInsideHeaders() throws Exception {
        byte[][] parts = headAndBody("426 Upgrade Required",
                "X-UltiCloud-Protocol-Supported: 2\r\nX-UltiCloud-Min-Framework: 6.3.1\r\n", BODY_426);
        byte[] all = concat(parts[0], parts[1]);
        byte[][] halves = split(all, "HTTP/1.1 426 Upgrade Required\r\nX-UltiCloud-Proto".length());
        Outcome outcome = run(Script.of(halves[0]).pause(PAUSE_MS).write(halves[1]).closeAfter());
        assertThat(outcome.status).isEqualTo(426);
        assertThat(outcome.rejection.code()).isEqualTo("upgrade_required");
        assertThat(outcome.rejection.requestId()).isEqualTo("req-426");
        assertThat(outcome.rejection.minFramework()).isEqualTo("6.3.1");
        assertThat(outcome.rejection.supportedProtocols()).containsExactly(2);
    }

    @Test
    @DisplayName("a 402 written as head and body in one write is read in full")
    void singleWrite() throws Exception {
        byte[][] parts = headAndBody("402 Payment Required", "", BODY_402);
        Outcome outcome = run(Script.of(concat(parts[0], parts[1])).closeAfter());
        assertFull402(outcome);
    }

    // ---------------------------------------------------------------- the status alone decides, without hanging

    @Test
    @DisplayName("a body over the cap is not waited for: the 402 arrives status-only while the server stays open")
    void bodyOverCap() throws Exception {
        StringBuilder big = new StringBuilder("{\"error\":{\"code\":\"plan_limit\",\"message\":\"");
        while (big.length() < UpgradeRejection.MAX_BODY_CHARS + 100) {
            big.append('x');
        }
        big.append("\"}}");
        byte[][] parts = headAndBody("402 Payment Required", "", big.toString());
        Outcome outcome = run(Script.of(parts[0]).pause(PAUSE_MS).write(parts[1]).holdOpen());
        assertStatusOnly(outcome, 402);
    }

    @Test
    @DisplayName("a refusal without Content-Length is not waited for: the status alone decides")
    void noContentLength() throws Exception {
        byte[] head = "HTTP/1.1 402 Payment Required\r\nContent-Type: application/json\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        Outcome outcome = run(Script.of(head).pause(PAUSE_MS).write(BODY_402.getBytes(StandardCharsets.UTF_8))
                .holdOpen());
        assertStatusOnly(outcome, 402);
    }

    @Test
    @DisplayName("a server that closes after the head of a refusal with a body is reported status-only at once")
    void serverClosesBeforeBody() throws Exception {
        byte[][] parts = headAndBody("402 Payment Required", "", BODY_402);
        Outcome outcome = run(Script.of(parts[0]).closeAfter());
        assertStatusOnly(outcome, 402);
    }

    @Test
    @DisplayName("a server that stalls after the head is bounded by closing the attempt (the handshake deadline)")
    void stalledBodyIsBoundedByTheDeadline() throws Exception {
        byte[][] parts = headAndBody("402 Payment Required", "", BODY_402);
        server = new ScriptedServer(Script.of(parts[0]).holdOpen());
        Events events = new Events();
        socket = open(server, events);
        assertThat(server.headWritten.await(WAIT_S, TimeUnit.SECONDS)).as("the head was written").isTrue();
        assertThat(events.queue.poll(400, TimeUnit.MILLISECONDS)).as("waiting for the announced body").isNull();
        long closedAt = System.currentTimeMillis();
        // AgentConnection does exactly this when its handshake deadline fires.
        socket.closeAttempt(AgentConnection.CLOSE_NORMAL, "handshake deadline");
        Outcome outcome = events.queue.poll(WAIT_S, TimeUnit.SECONDS);
        assertThat(outcome).as("the attempt ends once it is closed").isNotNull();
        assertThat(System.currentTimeMillis() - closedAt).isLessThan(2000);
        assertStatusOnly(outcome, 402);
    }

    // ---------------------------------------------------------------- helpers

    private static void assertFull402(Outcome outcome) {
        assertThat(outcome.status).isEqualTo(402);
        assertThat(outcome.rejection.code()).isEqualTo("plan_limit");
        assertThat(outcome.rejection.requestId()).isEqualTo("req-402");
        assertThat(outcome.rejection.planReason()).isEqualTo("pro_expired_over_limit");
        assertThat(outcome.rejection.graceEndedAt()).isEqualTo("2026-10-20T00:00:00.000Z");
        assertThat(outcome.rejection.manageUrl()).isEqualTo("https://panel.ultikits.com/billing");
    }

    private static void assertStatusOnly(Outcome outcome, int status) {
        assertThat(outcome.status).isEqualTo(status);
        assertThat(outcome.rejection.status()).isEqualTo(status);
        assertThat(outcome.rejection.code()).isNull();
        assertThat(outcome.rejection.planReason()).isNull();
        assertThat(outcome.rejection.manageUrl()).isNull();
    }

    private Outcome run(Script script) throws Exception {
        server = new ScriptedServer(script);
        Events events = new Events();
        long started = System.currentTimeMillis();
        socket = open(server, events);
        Outcome outcome = events.queue.poll(WAIT_S, TimeUnit.SECONDS);
        assertThat(outcome).as("the refusal is reported without hanging").isNotNull();
        assertThat(System.currentTimeMillis() - started).isLessThan(3000);
        return outcome;
    }

    private static AgentSocket open(ScriptedServer server, Events events) {
        AgentSocket s = new AgentSocket(URI.create("ws://127.0.0.1:" + server.port() + "/v1/agent"),
                new HashMap<String, String>(), 2000, events, (level, message) -> { });
        s.connect();
        return s;
    }

    /** @return {head, body}; the head carries Content-Type and the body's Content-Length */
    static byte[][] headAndBody(String status, String headers, String body) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + "\r\n" + headers + "Content-Type: application/json\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n\r\n";
        return new byte[][]{head.getBytes(StandardCharsets.ISO_8859_1), bodyBytes};
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static byte[][] split(byte[] all, int at) {
        return new byte[][]{Arrays.copyOfRange(all, 0, at), Arrays.copyOfRange(all, at, all.length)};
    }

    /** The outcome the socket reported. */
    static final class Outcome {
        final int status;
        final UpgradeRejection rejection;

        Outcome(int status, UpgradeRejection rejection) {
            this.status = status;
            this.rejection = rejection;
        }
    }

    /** Records the attempt's single outcome; a close that is not a refusal is recorded as status 0. */
    static final class Events implements AgentConnection.SocketEvents {
        final BlockingQueue<Outcome> queue = new LinkedBlockingQueue<>();

        @Override
        public void onOpen() {
            queue.add(new Outcome(101, UpgradeRejection.statusOnly(101)));
        }

        @Override
        public void onText(String text) {
            // not expected: every script refuses the upgrade
        }

        @Override
        public void onUpgradeRefused(int httpStatus, UpgradeRejection rejection) {
            queue.add(new Outcome(httpStatus, rejection));
        }

        @Override
        public void onClosed(int code, String reason, boolean remote) {
            queue.add(new Outcome(0, UpgradeRejection.statusOnly(0)));
        }
    }

    /** What the raw server writes after reading the request head. */
    static final class Script {
        final List<Object> steps = new ArrayList<>();
        boolean hold;

        static Script of(byte[] first) {
            Script s = new Script();
            s.steps.add(first);
            return s;
        }

        Script pause(long ms) {
            steps.add(ms);
            return this;
        }

        Script write(byte[] bytes) {
            steps.add(bytes);
            return this;
        }

        /** Closes the connection after the last write. */
        Script closeAfter() {
            hold = false;
            return this;
        }

        /** Keeps the connection open after the last write until the client goes away. */
        Script holdOpen() {
            hold = true;
            return this;
        }
    }

    /** A one-connection raw loopback server that plays a {@link Script}, flushing after every write. */
    static final class ScriptedServer implements AutoCloseable {
        final ServerSocket listener;
        final CountDownLatch headWritten = new CountDownLatch(1);
        private final Script script;
        private volatile Socket accepted;

        ScriptedServer(Script script) throws IOException {
            this.script = script;
            this.listener = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
            Thread t = new Thread(this::serve, "scripted-refusal-server");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        private void serve() {
            try (Socket s = listener.accept()) {
                accepted = s;
                s.setSoTimeout(10000);
                readHead(s.getInputStream());
                OutputStream out = s.getOutputStream();
                for (Object step : script.steps) {
                    if (step instanceof Long) {
                        Thread.sleep((Long) step);
                    } else {
                        out.write((byte[]) step);
                        out.flush();
                        headWritten.countDown();
                    }
                }
                if (script.hold) {
                    InputStream in = s.getInputStream();
                    while (in.read() >= 0) {
                        // hold the connection until the client closes it
                    }
                }
            } catch (IOException | InterruptedException e) {
                // the client went away or the test is over
            }
        }

        private static void readHead(InputStream in) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int matched = 0;
            byte[] end = {'\r', '\n', '\r', '\n'};
            int b;
            while ((b = in.read()) >= 0) {
                buf.write(b);
                matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
                if (matched == 4) {
                    return;
                }
            }
        }

        @Override
        public void close() throws IOException {
            listener.close();
            Socket s = accepted;
            if (s != null) {
                s.close();
            }
        }
    }
}
