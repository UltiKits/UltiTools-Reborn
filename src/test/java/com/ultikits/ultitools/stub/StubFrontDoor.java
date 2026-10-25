package com.ultikits.ultitools.stub;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;

/**
 * The stub's one public port (plan 22-19): a raw {@link ServerSocket} acceptor on loopback that
 * reads each connection's HTTP request head and routes it.
 *
 * <ul>
 *   <li>A v1 path ({@code /ws}, {@code /auth/*}, {@code /editor/*}, {@code /server/*},
 *       {@code /oauth/*}, {@code /plugin/*}) is answered 410 by the front door itself and logged
 *       {@code V1}, so a full session can prove that no v1 request was made.</li>
 *   <li>A WebSocket upgrade of {@code /v1/agent} is decided in the contract's order
 *       ({@code PROTOCOL.md} section 10: protocol, framework, credential, plan). A refusal is
 *       written by the front door as raw HTTP, with the vendored {@code agentUpgrade/error} body of
 *       that status and the contract's response headers; Java-WebSocket's server can only refuse
 *       with 404 or 500 ({@code WebSocketImpl#generateHttpResponseDueToError}, checked with
 *       {@code javap}). An accepted upgrade is relayed byte for byte to {@link StubGateway}.</li>
 *   <li>{@code /v1/catalogue/*} is relayed to {@link StubCatalogue}; every other request to the
 *       internal HTTP server of {@link StubDeviceAuthority}.</li>
 * </ul>
 *
 * <p>Relayed HTTP requests are served one per connection: the front door rewrites both heads to
 * {@code Connection: close}, so a client that pools connections (as {@code HttpURLConnection}
 * does) can never send its next request down the previous request's relay. Every request line and
 * every response status is logged, with the {@code Authorization} value masked.
 *
 * @since 6.3.1
 */
public final class StubFrontDoor {

    /** The agent socket path. */
    public static final String AGENT_PATH = "/v1/agent";
    /** The catalogue prefix, relayed to {@link StubCatalogue}. */
    public static final String CATALOGUE_PREFIX = "/v1/catalogue/";

    static final String PROTOCOL_HEADER = "X-UltiCloud-Protocol";
    static final String FRAMEWORK_HEADER = "X-UltiCloud-Framework";
    static final String SUPPORTED_HEADER = "X-UltiCloud-Protocol-Supported";
    static final String MIN_FRAMEWORK_HEADER = "X-UltiCloud-Min-Framework";
    static final String RETRY_AFTER_HEADER = "Retry-After";

    private static final List<String> V1_PREFIXES = Collections.unmodifiableList(java.util.Arrays.asList(
            "/auth/", "/editor/", "/server/", "/oauth/", "/plugin/"));
    private static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?$");
    private static final int MAX_HEAD_BYTES = 32 * 1024;
    /** CR LF CR LF, the end of an HTTP head, as four bytes. */
    private static final int END_OF_HEAD = 0x0D0A0D0A;
    private static final int HEAD_TIMEOUT_MS = 15_000;
    private static final int HTTP_TIMEOUT_MS = 60_000;

    private final UltiCloudStub.Contract contract;
    private final StubAccessLog log;
    private final UltiCloudStub.Credentials credentials;
    private final int gatewayPort;
    private final int httpPort;
    private final int cataloguePort;
    private final ExecutorService workers = Executors.newCachedThreadPool(
            UltiCloudStub.daemonThreads("ulticloud-stub-front-door"));
    private final Set<Socket> open = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private volatile ServerSocket listener;
    private volatile int port;

    StubFrontDoor(int port, UltiCloudStub.Contract contract, StubAccessLog log, UltiCloudStub.Credentials credentials,
                  int gatewayPort, int httpPort, int cataloguePort) {
        this.port = port;
        this.contract = contract;
        this.log = log;
        this.credentials = credentials;
        this.gatewayPort = gatewayPort;
        this.httpPort = httpPort;
        this.cataloguePort = cataloguePort;
    }

    synchronized void start() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50);
        port = socket.getLocalPort();
        listener = socket;
        Thread acceptor = new Thread(() -> accept(socket), "ulticloud-stub-acceptor-" + port);
        acceptor.setDaemon(true);
        acceptor.start();
    }

    synchronized void stop() {
        ServerSocket socket = listener;
        listener = null;
        closeQuietly(socket);
        dropOpenConnections();
        workers.shutdownNow();
    }

    /** @return the public port */
    public int port() {
        return port;
    }

    /** @return the bound public address, a loopback address */
    public InetSocketAddress address() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    void dropOpenConnections() {
        for (Socket socket : new ArrayList<>(open)) {
            closeQuietly(socket);
        }
    }

    private void accept(ServerSocket socket) {
        while (!socket.isClosed()) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException closed) {
                return;
            }
            open.add(client);
            try {
                workers.execute(() -> serve(client));
            } catch (RuntimeException rejected) {
                closeQuietly(client);
            }
        }
    }

    // ------------------------------------------------------------------ routing

    private void serve(Socket client) {
        try {
            client.setSoTimeout(HEAD_TIMEOUT_MS);
            BufferedInputStream in = new BufferedInputStream(client.getInputStream());
            Head head = Head.read(in);
            if (head == null) {
                return;
            }
            String path = head.path();
            if (isV1(path)) {
                log.log("V1", head.method + " " + head.target + " -> 410");
                writeJson(client.getOutputStream(), 410, "Gone", gone(), null);
                return;
            }
            if (AGENT_PATH.equals(path) && head.isWebSocketUpgrade()) {
                upgrade(client, in, head);
                return;
            }
            log.log("HTTP", head.method + " " + head.target + " auth=" + describeAuth(head));
            relayHttp(client, in, head, path.startsWith(CATALOGUE_PREFIX) ? cataloguePort : httpPort);
        } catch (IOException | RuntimeException failure) {
            // A client that went away mid-request ends its own connection; nothing else is affected.
        } finally {
            closeQuietly(client);
        }
    }

    private void upgrade(Socket client, BufferedInputStream in, Head head) throws IOException {
        Refusal refusal = decide(head);
        String summary = "GET " + head.target + " protocol=" + head.header(PROTOCOL_HEADER)
                + " framework=" + head.header(FRAMEWORK_HEADER) + " auth=" + describeAuth(head);
        if (refusal != null) {
            log.log("UPGRADE", summary + " -> " + refusal.status + " (" + refusal.reason + ")");
            refusal.write(client.getOutputStream(), contract, log);
            return;
        }
        log.log("UPGRADE", summary + " -> 101");
        Socket upstream = new Socket(InetAddress.getLoopbackAddress(), gatewayPort);
        open.add(upstream);
        try {
            client.setSoTimeout(0);
            upstream.getOutputStream().write(head.raw);
            upstream.getOutputStream().flush();
            pipeInBackground(in, upstream.getOutputStream(), upstream);
            copy(upstream.getInputStream(), client.getOutputStream());
        } finally {
            closeQuietly(upstream);
        }
    }

    /**
     * Decides an upgrade in the contract's order (section 10).
     *
     * @param head the upgrade request head
     * @return the refusal, or {@code null} to accept
     */
    Refusal decide(Head head) {
        String protocol = head.header(PROTOCOL_HEADER);
        if (protocol == null || !"2".equals(protocol.trim())) {
            return Refusal.upgradeRequired(protocol == null ? "no " + PROTOCOL_HEADER + " header"
                    : PROTOCOL_HEADER + " is not 2", null);
        }
        String framework = head.header(FRAMEWORK_HEADER);
        if (framework == null || !SEMVER.matcher(framework.trim()).matches()) {
            return Refusal.upgradeRequired(framework == null ? "no " + FRAMEWORK_HEADER + " header"
                    : FRAMEWORK_HEADER + " is not a semantic version", null);
        }
        String credential = StubDeviceAuthority.bearer(head.header("Authorization"));
        if (credentials.serverIdOf(credential) == null) {
            return Refusal.of(401, "the credential is unknown or revoked");
        }
        return null;
    }

    private void relayHttp(Socket client, BufferedInputStream in, Head head, int target) throws IOException {
        client.setSoTimeout(HTTP_TIMEOUT_MS);
        Socket upstream = new Socket(InetAddress.getLoopbackAddress(), target);
        open.add(upstream);
        try {
            relayHttp(client, in, head, upstream);
        } finally {
            closeQuietly(upstream);
        }
    }

    private void relayHttp(Socket client, BufferedInputStream in, Head head, Socket upstream) throws IOException {
        upstream.setSoTimeout(HTTP_TIMEOUT_MS);
        OutputStream toUpstream = upstream.getOutputStream();
        toUpstream.write(head.withConnectionClose());
        toUpstream.flush();
        pipeInBackground(in, toUpstream, upstream);
        BufferedInputStream fromUpstream = new BufferedInputStream(upstream.getInputStream());
        Head response = Head.read(fromUpstream);
        if (response == null) {
            log.log("HTTP-RESPONSE", "none " + head.method + " " + head.target);
            return;
        }
        log.log("HTTP-RESPONSE", response.status() + " " + head.method + " " + head.target);
        OutputStream toClient = client.getOutputStream();
        toClient.write(response.withConnectionClose());
        copy(fromUpstream, toClient);
    }

    private void pipeInBackground(InputStream from, OutputStream to, Socket closeWhenDone) {
        try {
            workers.execute(() -> {
                try {
                    copy(from, to);
                } catch (IOException ended) {
                    // One side closed; the other side's copy ends the relay.
                } finally {
                    try {
                        closeWhenDone.shutdownOutput();
                    } catch (IOException alreadyClosed) {
                        // Nothing left to flush.
                    }
                }
            });
        } catch (RuntimeException rejected) {
            closeQuietly(closeWhenDone);
        }
    }

    private static void copy(InputStream from, OutputStream to) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = from.read(buffer)) != -1) {
            to.write(buffer, 0, read);
            to.flush();
        }
    }

    private static boolean isV1(String path) {
        if ("/ws".equals(path) || path.startsWith("/ws/")) {
            return true;
        }
        for (String prefix : V1_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject gone() {
        JsonObject error = new JsonObject();
        error.addProperty("code", "gone");
        error.addProperty("message", "The v1 UltiCloud API is gone; this stub serves protocol v2 only.");
        error.addProperty("requestId", StubDeviceAuthority.requestId());
        JsonObject body = new JsonObject();
        body.add("error", error);
        return body;
    }

    private static String describeAuth(Head head) {
        String authorization = head.header("Authorization");
        return authorization == null ? "none" : authorization;
    }

    static void writeJson(OutputStream out, int status, String reason, JsonObject body, List<String> headers)
            throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder()
                .append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
                .append("Content-Type: application/json; charset=utf-8\r\n")
                .append("Content-Length: ").append(bytes.length).append("\r\n")
                .append("Connection: close\r\n");
        if (headers != null) {
            for (String header : headers) {
                head.append(header).append("\r\n");
            }
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Closing is best effort.
        }
    }

    private void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        open.remove(socket);
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing is best effort.
        }
    }

    // ------------------------------------------------------------------ types

    /** A refused upgrade: status, reason for the log, and how the vendored body is adapted. */
    static final class Refusal {
        final int status;
        final String reason;
        final String minFramework;
        final long retryAfterSeconds;

        private Refusal(int status, String reason, String minFramework, long retryAfterSeconds) {
            this.status = status;
            this.reason = reason;
            this.minFramework = minFramework;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        static Refusal of(int status, String reason) {
            return new Refusal(status, reason, null, 0);
        }

        static Refusal upgradeRequired(String reason, String minFramework) {
            return new Refusal(426, reason, minFramework, 0);
        }

        void write(OutputStream out, UltiCloudStub.Contract contract, StubAccessLog log) throws IOException {
            String fixture = contract.fileNames("fixtures/agent-rest/agentUpgrade/error/valid/").stream()
                    .filter(name -> name.startsWith(status + "-")).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No vendored " + status + " upgrade refusal"));
            JsonObject body = contract.object("fixtures/agent-rest/agentUpgrade/error/valid/" + fixture);
            JsonObject error = body.getAsJsonObject("error");
            error.addProperty("requestId", StubDeviceAuthority.requestId());
            List<String> headers = new ArrayList<>();
            if (status == 426) {
                JsonObject details = error.getAsJsonObject("details");
                if (minFramework != null) {
                    details.addProperty("minFramework", minFramework);
                }
                StringBuilder supported = new StringBuilder();
                for (int i = 0; i < details.getAsJsonArray("supportedProtocols").size(); i++) {
                    supported.append(i == 0 ? "" : ",").append(details.getAsJsonArray("supportedProtocols").get(i));
                }
                headers.add(SUPPORTED_HEADER + ": " + supported);
                headers.add(MIN_FRAMEWORK_HEADER + ": " + details.get("minFramework").getAsString());
            }
            if (status == 429) {
                headers.add(RETRY_AFTER_HEADER + ": " + retryAfterSeconds);
            }
            String schema = "schemas/agent-rest/agentUpgrade.error." + status + ".schema.json";
            String issue = contract.firstIssue(schema, body);
            if (issue != null) {
                log.log("REST-SELF-INVALID", "agentUpgrade.error." + status + " " + issue);
            }
            writeJson(out, status, reasonPhrase(status), body, headers);
        }

        private static String reasonPhrase(int status) {
            switch (status) {
                case 401:
                    return "Unauthorized";
                case 402:
                    return "Payment Required";
                case 426:
                    return "Upgrade Required";
                case 429:
                    return "Too Many Requests";
                default:
                    return "Refused";
            }
        }
    }

    /** One HTTP head as read from the wire: start line, header lines and the raw bytes. */
    static final class Head {
        final byte[] raw;
        final String startLine;
        final String method;
        final String target;
        final List<String> headerLines;

        private Head(byte[] raw, String startLine, List<String> headerLines) {
            this.raw = raw;
            this.startLine = startLine;
            this.headerLines = headerLines;
            String[] parts = startLine.split(" ", 3);
            this.method = parts[0];
            this.target = parts.length > 1 ? parts[1] : "";
        }

        /**
         * Reads one head, up to and including the empty line.
         *
         * @return the head, or {@code null} at end of stream before a complete head
         */
        static Head read(InputStream in) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int lastFour = 0;
            int value;
            while ((value = in.read()) != -1) {
                bytes.write(value);
                if (bytes.size() > MAX_HEAD_BYTES) {
                    return null;
                }
                lastFour = (lastFour << 8) | value;
                if (lastFour == END_OF_HEAD) {
                    byte[] raw = bytes.toByteArray();
                    String text = new String(raw, 0, raw.length - 4, StandardCharsets.ISO_8859_1);
                    String[] lines = text.split("\r\n");
                    List<String> headers = new ArrayList<>();
                    for (int i = 1; i < lines.length; i++) {
                        headers.add(lines[i]);
                    }
                    return new Head(raw, lines[0], headers);
                }
            }
            return null;
        }

        /** @return the target's path, without the query */
        String path() {
            int query = target.indexOf('?');
            return query < 0 ? target : target.substring(0, query);
        }

        /** @return the response status, for a response head */
        String status() {
            String[] parts = startLine.split(" ", 3);
            return parts.length > 1 ? parts[1] : "?";
        }

        /**
         * @param name a header name, any case
         * @return its first value, trimmed, or {@code null}
         */
        String header(String name) {
            for (String line : headerLines) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                    return line.substring(colon + 1).trim();
                }
            }
            return null;
        }

        boolean isWebSocketUpgrade() {
            String upgrade = header("Upgrade");
            return upgrade != null && upgrade.toLowerCase(Locale.ROOT).contains("websocket");
        }

        /** @return the head re-serialised with every connection header replaced by {@code Connection: close} */
        byte[] withConnectionClose() {
            StringBuilder text = new StringBuilder(startLine).append("\r\n");
            for (String line : headerLines) {
                int colon = line.indexOf(':');
                String name = colon > 0 ? line.substring(0, colon).trim() : line;
                if (!name.equalsIgnoreCase("Connection") && !name.equalsIgnoreCase("Keep-Alive")
                        && !name.equalsIgnoreCase("Proxy-Connection")) {
                    text.append(line).append("\r\n");
                }
            }
            text.append("Connection: close\r\n\r\n");
            return text.toString().getBytes(StandardCharsets.ISO_8859_1);
        }
    }
}
