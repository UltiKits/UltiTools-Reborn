package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.JsonObject;

/** Exercises the real JDK transport: an HTTP stub cannot reveal a hidden POST retry reliably. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class CloudHttpTest {

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
    }

    @AfterEach
    void tearDown() {
        HttpRequestUtils.resetBaseUrl();
    }

    @Test
    void nullBodyPostIsNotRetriedAfterConnectionReset() throws Exception {
        assertResetIsNotRetried(null, "/v1/agent/credential/revoke");
    }

    @Test
    void jsonBodyPostIsNotRetriedAfterConnectionReset() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("deviceCode", "dc_test");
        assertResetIsNotRetried(body, "/v1/device/token");
    }

    @Test
    void malformedBearerIsMappedToIoErrorWithoutEscapingInAnException() {
        HttpRequestUtils.setBaseUrlForTesting("http://127.0.0.1:1");
        String bearer = "ucs_HEADER_TEST\r\nInjected: secret";

        CloudHttp.Response response = CloudHttp.postJson("/v1/agent/credential/revoke", null, bearer);

        assertThat(response.outcome()).isEqualTo(CloudHttp.Outcome.IO_ERROR);
        assertThat(response.status()).isEqualTo(-1);
        assertThat(response.body()).isNull();
        assertThat(response.toString()).doesNotContain("ucs_HEADER_TEST", "secret");
    }

    private static void assertResetIsNotRetried(JsonObject body, String path) throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(2000);
            ExecutorService receiver = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> accepted = receiver.submit(() -> resetRequests(listener, path));
                HttpRequestUtils.setBaseUrlForTesting("http://127.0.0.1:" + listener.getLocalPort());

                CloudHttp.Response response = CloudHttp.postJson(path, body, "ucs_RESET_TEST");
                int connections = accepted.get(5, TimeUnit.SECONDS);

                String bodyKind = body == null ? "null" : "JSON";
                System.out.println("CloudHttp TCP reset: body=" + bodyKind + ", connections=" + connections
                        + ", outcome=" + response.outcome());
                assertThat(response.outcome()).isEqualTo(CloudHttp.Outcome.IO_ERROR);
                assertThat(response.status()).isEqualTo(-1);
                assertThat(response.body()).isNull();
                assertThat(response.toString()).doesNotContain("ucs_RESET_TEST");
                assertThat(connections).as("%s-body POST connections after TCP reset", bodyKind).isEqualTo(1);
            } finally {
                listener.close();
                receiver.shutdownNow();
                assertThat(receiver.awaitTermination(3, TimeUnit.SECONDS))
                        .as("the reset server thread terminates").isTrue();
            }
        }
    }

    private static int resetRequests(ServerSocket listener, String path) throws IOException {
        int connections = 0;
        long deadline = 0;
        while (true) {
            if (connections > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return connections;
                }
                listener.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            }
            Socket accepted;
            try {
                accepted = listener.accept();
            } catch (SocketTimeoutException timeout) {
                if (connections == 0) {
                    throw timeout;
                }
                return connections;
            }
            try (Socket socket = accepted) {
                connections++;
                socket.setSoTimeout(2000);
                String head = readRequestHead(socket.getInputStream());
                assertThat(head).startsWith("POST " + path + " HTTP/1.1\r\n");
                socket.setSoLinger(true, 0);
                if (connections == 1) {
                    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                }
                // Closing with SO_LINGER(0) resets TCP without sending any HTTP response.
            }
        }
    }

    private static String readRequestHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while (head.size() < 16 * 1024) {
            int next = in.read();
            if (next == -1) {
                throw new EOFException("Connection ended before the complete HTTP request head");
            }
            head.write(next);
            matched = next == end[matched] ? matched + 1 : (next == '\r' ? 1 : 0);
            if (matched == end.length) {
                return new String(head.toByteArray(), StandardCharsets.US_ASCII);
            }
        }
        throw new IOException("HTTP request head exceeds the test limit");
    }
}
