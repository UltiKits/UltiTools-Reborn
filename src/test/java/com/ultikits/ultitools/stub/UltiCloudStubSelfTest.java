package com.ultikits.ultitools.stub;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.ContractSchemas;

/**
 * Proves the UAT stub of plan 22-19 against the frozen contract: a v2 client logs in, connects,
 * handshakes and gets an event acknowledged, everything appears in the access log, and every body
 * and frame the stub answers validates against the vendored JSON Schema.
 *
 * <p>The checks here use the contract helpers of plan 22-08 ({@link ContractSchemas},
 * {@link ContractFixtures}), not the stub's own validator, so a stub that drifted from the
 * contract fails here rather than passing its own reading of it.
 */
class UltiCloudStubSelfTest {

    private static final String FRAMEWORK = "6.3.1";
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";

    @TempDir
    Path temp;

    private UltiCloudStub.Handle stub;
    private final List<AgentClient> clients = new ArrayList<>();

    @BeforeEach
    void startStub() throws IOException {
        stub = UltiCloudStub.start(UltiCloudStub.Options.defaults().accessLog(temp.resolve("access.log")));
    }

    @AfterEach
    void stopStub() {
        for (AgentClient client : clients) {
            client.close();
        }
        if (stub != null) {
            stub.close();
        }
    }

    // ------------------------------------------------------------------ Task 1: the tracer

    @Test
    void deviceLoginThenUpgradeHelloWelcomePingAndAnAckedEventAreAllLogged() throws Exception {
        Login login = login();

        AgentClient client = connect(login.credential);
        JsonObject hello = hello("events:players");
        client.send(hello);
        JsonObject welcome = client.nextFrame(5000);
        assertThat(welcome.get("type").getAsString()).isEqualTo("welcome");
        assertThat(welcome.get("replyTo").getAsString()).isEqualTo(hello.get("id").getAsString());
        assertThat(welcome.getAsJsonObject("payload").get("serverId").getAsString()).isEqualTo(login.serverId);
        ContractSchemas.assertFrameValid(welcome);

        client.sendText("ping");
        assertThat(client.nextText(5000)).isEqualTo("pong");

        JsonObject join = frame("player.join", "basic.json");
        long sentAt = System.nanoTime();
        client.send(join);
        JsonObject ack = client.nextFrame(1000);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sentAt);
        assertThat(ack.get("type").getAsString()).isEqualTo("ack");
        assertThat(ack.getAsJsonObject("payload").getAsJsonArray("ids")).containsExactly(join.get("id"));
        assertThat(elapsedMs).isLessThan(1000);
        ContractSchemas.assertFrameValid(ack);

        List<String> lines = stub.accessLog().lines();
        assertInOrder(lines,
                "HTTP POST /v1/device/authorize",
                "HTTP POST /v1/device/token",
                "HTTP POST /v1/device/token",
                "UPGRADE GET /v1/agent",
                "WS-IN conn=1 hello",
                "WS-OUT conn=1 welcome",
                "WS-IN conn=1 ping",
                "WS-OUT conn=1 pong",
                "WS-IN conn=1 player.join",
                "WS-OUT conn=1 ack");
        String memory = String.join("\n", lines);
        String onDisk = new String(Files.readAllBytes(temp.resolve("access.log")), StandardCharsets.UTF_8);
        for (String text : new String[]{memory, onDisk}) {
            assertThat(text).doesNotContain(login.credential)
                    .contains("ucs_***#" + StubAccessLog.fingerprint(login.credential));
        }
        assertThat(onDisk).contains("WS-OUT conn=1 ack");
        assertThat(lines.get(0)).matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z .*");
    }

    @Test
    void aRequestBodyThatFailsItsSchemaIsLoggedInvalidWithTheSchemasFirstIssue() throws Exception {
        JsonObject body = ContractFixtures.object("fixtures/agent-rest/deviceAuthorize/request/valid/server.json");
        String legacy = body.remove("legacyServerUuid").getAsString();
        body.addProperty("attachToServerId", legacy);

        HttpReply reply = RawHttp.post(stub.httpPort(), "/v1/device/authorize", body.toString(), null);

        assertThat(reply.status).isEqualTo(400);
        assertThat(reply.json().getAsJsonObject("error").get("code").getAsString()).isEqualTo("invalid_request");
        ContractSchemas.assertRestBodyValid("deviceAuthorize.error.400.schema.json", reply.json());
        assertThat(stub.accessLog().lines()).anySatisfy(line -> assertThat(line)
                .contains("REST-INVALID deviceAuthorize.request")
                .contains("attachToServerId"));
    }

    @Test
    void aValidRequestBodyIsLoggedValid() throws Exception {
        login();
        assertThat(stub.accessLog().lines())
                .anySatisfy(line -> assertThat(line).contains("REST-VALID deviceAuthorize.request"))
                .anySatisfy(line -> assertThat(line).contains("REST-VALID deviceToken.request"));
    }

    @Test
    void pooledHttpConnectionsNeverCarryARequestToTheWrongRoute() throws Exception {
        // HttpURLConnection keeps connections alive; the front door must close each one, so a second
        // request to another route is not sent down the first route's relay.
        for (int i = 0; i < 3; i++) {
            java.net.HttpURLConnection authorize = (java.net.HttpURLConnection) new java.net.URL(
                    stub.baseUrl() + "/v1/device/authorize").openConnection();
            authorize.setRequestMethod("POST");
            authorize.setDoOutput(true);
            authorize.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = ContractFixtures.text("fixtures/agent-rest/deviceAuthorize/request/valid/server.json")
                    .getBytes(StandardCharsets.UTF_8);
            authorize.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = authorize.getOutputStream()) {
                out.write(bytes);
            }
            assertThat(authorize.getResponseCode()).isEqualTo(200);
            JsonObject issued = JsonParser.parseString(readAll(authorize.getInputStream())).getAsJsonObject();
            ContractSchemas.assertRestBodyValid("deviceAuthorize.response.200.schema.json", issued);

            java.net.HttpURLConnection framework = (java.net.HttpURLConnection) new java.net.URL(
                    stub.baseUrl() + "/v1/catalogue/framework").openConnection();
            assertThat(framework.getResponseCode()).isEqualTo(200);
            assertThat(readAll(framework.getInputStream())).contains("\"version\"");
        }
        assertThat(stub.catalogue().paths()).containsExactly(
                "/v1/catalogue/framework", "/v1/catalogue/framework", "/v1/catalogue/framework");
    }

    // ------------------------------------------------------------------ helpers

    private Login login() throws IOException {
        HttpReply authorize = RawHttp.post(stub.httpPort(), "/v1/device/authorize",
                ContractFixtures.text("fixtures/agent-rest/deviceAuthorize/request/valid/server.json"), null);
        assertThat(authorize.status).isEqualTo(200);
        ContractSchemas.assertRestBodyValid("deviceAuthorize.response.200.schema.json", authorize.json());
        String deviceCode = authorize.json().getAsJsonObject("data").get("deviceCode").getAsString();
        JsonObject poll = new JsonObject();
        poll.addProperty("deviceCode", deviceCode);

        HttpReply pending = RawHttp.post(stub.httpPort(), "/v1/device/token", poll.toString(), null);
        assertThat(pending.status).isEqualTo(400);
        ContractSchemas.assertRestBodyValid("deviceToken.error.400.schema.json", pending.json());
        assertThat(pending.json().getAsJsonObject("error").get("code").getAsString())
                .isEqualTo("authorization_pending");

        HttpReply issued = RawHttp.post(stub.httpPort(), "/v1/device/token", poll.toString(), null);
        assertThat(issued.status).isEqualTo(200);
        ContractSchemas.assertRestBodyValid("deviceToken.response.200.schema.json", issued.json());
        JsonObject data = issued.json().getAsJsonObject("data");
        assertThat(data.get("kind").getAsString()).isEqualTo("server");
        assertThat(data.get("credential").getAsString()).startsWith("ucs_");
        assertThat(data.get("serverId").getAsString()).matches(ULID);
        return new Login(data.get("credential").getAsString(), data.get("serverId").getAsString());
    }

    private AgentClient connect(String credential) throws InterruptedException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + credential);
        headers.put("X-UltiCloud-Protocol", "2");
        headers.put("X-UltiCloud-Framework", FRAMEWORK);
        AgentClient client = new AgentClient(URI.create("ws://127.0.0.1:" + stub.httpPort() + "/v1/agent"), headers);
        clients.add(client);
        assertThat(client.connectBlocking(5, TimeUnit.SECONDS)).as("the upgrade is accepted").isTrue();
        return client;
    }

    /** The vendored basic hello with a fresh id, also declaring {@code extra} capabilities. */
    static JsonObject hello(String... extra) {
        JsonObject hello = frame("hello", "basic.json");
        for (String capability : extra) {
            hello.getAsJsonObject("payload").getAsJsonArray("capabilities").add(capability);
        }
        return hello;
    }

    /** A vendored valid frame with a fresh id, so each send is a new message. */
    static JsonObject frame(String type, String fixture) {
        JsonObject frame = ContractFixtures.object("fixtures/agent/" + type + "/valid/" + fixture);
        frame.addProperty("id", UltiCloudStub.Contract.newUlid());
        frame.addProperty("ts", System.currentTimeMillis());
        return frame;
    }

    private static void assertInOrder(List<String> lines, String... fragments) {
        int from = 0;
        for (String fragment : fragments) {
            int found = -1;
            for (int i = from; i < lines.size(); i++) {
                if (lines.get(i).contains(fragment)) {
                    found = i;
                    break;
                }
            }
            assertThat(found).as("'%s' after line %d in%n%s", fragment, from, String.join("\n", lines))
                    .isGreaterThanOrEqualTo(0);
            from = found + 1;
        }
    }

    static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            bytes.write(buffer, 0, read);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final class Login {
        final String credential;
        final String serverId;

        Login(String credential, String serverId) {
            this.credential = credential;
            this.serverId = serverId;
        }
    }

    /** One answer read by {@link RawHttp}: status line, headers (lower-case names) and body. */
    static final class HttpReply {
        final int status;
        final Map<String, String> headers;
        final String body;

        HttpReply(int status, Map<String, String> headers, String body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        JsonObject json() {
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    /** A minimal HTTP/1.1 client over a raw socket, so the test sees exactly what the stub wrote. */
    static final class RawHttp {
        private RawHttp() {
        }

        static HttpReply post(int port, String path, String body, Map<String, String> headers) throws IOException {
            return request(port, "POST", path, body, headers);
        }

        static HttpReply get(int port, String path, Map<String, String> headers) throws IOException {
            return request(port, "GET", path, null, headers);
        }

        static HttpReply request(int port, String method, String path, String body, Map<String, String> headers)
                throws IOException {
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder()
                    .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                    .append("Host: 127.0.0.1:").append(port).append("\r\n");
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
                }
            }
            if (body != null) {
                head.append("Content-Type: application/json\r\nContent-Length: ").append(bytes.length).append("\r\n");
            }
            head.append("\r\n");
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                socket.setSoTimeout(10_000);
                OutputStream out = socket.getOutputStream();
                out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
                out.write(bytes);
                out.flush();
                return parse(readBytes(socket.getInputStream()));
            }
        }

        private static byte[] readBytes(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
            }
            return bytes.toByteArray();
        }

        private static HttpReply parse(byte[] raw) {
            String text = new String(raw, StandardCharsets.ISO_8859_1);
            int end = text.indexOf("\r\n\r\n");
            assertThat(end).as("a complete response head in %s", text).isGreaterThan(0);
            String[] lines = text.substring(0, end).split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ")[1]);
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(colon + 1).trim());
            }
            byte[] body = new byte[raw.length - end - 4];
            System.arraycopy(raw, end + 4, body, 0, body.length);
            return new HttpReply(status, headers, new String(body, StandardCharsets.UTF_8));
        }
    }

    /** A Java-WebSocket client that queues every text frame and records how the connection closed. */
    static final class AgentClient extends WebSocketClient {
        private final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile int closeCode = -1;
        private volatile String closeReason;

        AgentClient(URI uri, Map<String, String> headers) {
            super(uri, new Draft_6455(), headers, 5000);
            setConnectionLostTimeout(0);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            // Nothing to do: frames are queued as they arrive.
        }

        @Override
        public void onMessage(String message) {
            texts.add(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            closeCode = code;
            closeReason = reason;
            closed.countDown();
        }

        @Override
        public void onError(Exception ex) {
            // The close callback carries the outcome the tests assert.
        }

        void send(JsonObject frame) {
            send(frame.toString());
        }

        void sendText(String text) {
            send(text);
        }

        String nextText(long timeoutMs) throws InterruptedException {
            String text = texts.poll(timeoutMs, TimeUnit.MILLISECONDS);
            assertThat(text).as("a text frame within %d ms", timeoutMs).isNotNull();
            return text;
        }

        String pollText(long timeoutMs) throws InterruptedException {
            return texts.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }

        JsonObject nextFrame(long timeoutMs) throws InterruptedException {
            return JsonParser.parseString(nextText(timeoutMs)).getAsJsonObject();
        }

        int awaitClose(long timeoutMs) throws InterruptedException {
            assertThat(closed.await(timeoutMs, TimeUnit.MILLISECONDS)).as("closed within %d ms", timeoutMs).isTrue();
            return closeCode;
        }

        String closeReason() {
            return closeReason;
        }

        JsonArray drainFrames() {
            JsonArray frames = new JsonArray();
            String text;
            while ((text = texts.poll()) != null) {
                if (text.startsWith("{")) {
                    frames.add(JsonParser.parseString(text));
                }
            }
            return frames;
        }
    }
}
