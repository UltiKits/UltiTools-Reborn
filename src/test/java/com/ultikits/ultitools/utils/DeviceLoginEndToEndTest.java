package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.commands.CloudLoginCommand;

/**
 * Phase 22 tracer (plan 22-01, Task 1): {@code /ulticloud login} end to end against a loopback
 * UltiCloud stub -- the command, the RFC 8628 device authorization request, two token polls and the
 * {@code ucs_} server credential written into {@code credentials.json} through {@link CredentialStore}.
 * <p>
 * The stub is the JDK's own {@code com.sun.net.httpserver}, so the request bodies are observed on the
 * wire, and MockBukkit supplies the server the command runs on. Every value is synthetic;
 * {@code ucs_TESTSECRET} and {@code dc-1} stand in for the secret and the device code, which must
 * never reach a log record or a sender message.
 */
@DisplayName("Device login end to end: command -> authorize/token stub -> credential file")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class DeviceLoginEndToEndTest {

    private static final String STORED_UUID = "0123456789abcdef0123456789abcdef";
    private static final String SECRET = "ucs_TESTSECRET";
    private static final String DEVICE_CODE = "dc-1";
    private static final String VERIFICATION_URI = "https://panel.ultikits.com/device";
    private static final String COMPLETE_URI = "https://panel.ultikits.com/device?code=BCDF-GHJK";
    private static final String USER_CODE = "BCDF-GHJK";

    @TempDir
    Path tempDir;

    private Path credentialFile;
    private HttpServer cloud;
    private final List<String[]> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger tokenPolls = new AtomicInteger();
    private final List<String> logLines = new CopyOnWriteArrayList<>();
    private final List<String> senderLines = new CopyOnWriteArrayList<>();

    /** Collects every record the plugin logger publishes, message and parameters formatted. */
    private final class CapturingHandler extends Handler {
        @Override
        public void publish(LogRecord record) {
            String message = record.getMessage();
            if (record.getParameters() != null) {
                message = new java.text.MessageFormat(message).format(record.getParameters());
            }
            if (record.getThrown() != null) {
                message = message + " | " + record.getThrown();
            }
            logLines.add(record.getLevel() + " " + message);
        }

        @Override
        public void flush() {
            // Nothing buffered.
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        // A real server always exists when /ulticloud login runs; MockBukkit supplies one (MOTD,
        // primary thread = this JUnit thread).
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        CloudSession.resetForTesting();
        ApiRateLimiter.resetAll();
        credentialFile = tempDir.resolve("credentials.json");
        Files.write(credentialFile, ("{\"uuid\":\"" + STORED_UUID + "\",\"cloud_token\":{\"access_token\":\"old\"}}")
                .getBytes(StandardCharsets.UTF_8));
        CredentialStore.setTargetPathForTesting(credentialFile);
        CredentialStore.setOldLocationForTesting(tempDir.resolve("pre-migration-data.json"));

        Logger logger = Logger.getLogger("DeviceLoginEndToEndTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new CapturingHandler());
        PluginDescriptionFile description =
                new PluginDescriptionFile("UltiTools", "6.3.1-SNAPSHOT", "com.ultikits.ultitools.UltiTools");
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(logger);
            lenient().when(ultiTools.getDescription()).thenReturn(description);
        });

        cloud = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cloud.createContext("/", this::answer);
        cloud.start();
        HttpRequestUtils.setBaseUrlForTesting("http://127.0.0.1:" + cloud.getAddress().getPort());
    }

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // clears the UltiTools singleton mocked in setUp
    void tearDown() throws Exception {
        CloudSession.resetForTesting();
        ApiRateLimiter.resetAll();
        HttpRequestUtils.resetBaseUrl();
        CredentialStore.clearTargetPathForTesting();
        CredentialStore.clearOldLocationForTesting();
        cloud.stop(0);
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
        MockBukkitHelper.safeUnmock();
    }

    private void answer(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            requests.add(new String[]{exchange.getRequestMethod() + " " + path, readBody(exchange)});
            if ("/v1/device/authorize".equals(path)) {
                send(exchange, 200, "{\"data\":{\"deviceCode\":\"" + DEVICE_CODE + "\",\"userCode\":\"" + USER_CODE
                        + "\",\"verificationUri\":\"" + VERIFICATION_URI + "\",\"verificationUriComplete\":\""
                        + COMPLETE_URI + "\",\"expiresIn\":600,\"interval\":1}}");
            } else if ("/v1/device/token".equals(path)) {
                if (tokenPolls.incrementAndGet() == 1) {
                    send(exchange, 400, "{\"error\":{\"code\":\"authorization_pending\","
                            + "\"message\":\"The owner has not confirmed yet\",\"requestId\":\"r\"}}");
                } else {
                    send(exchange, 200, "{\"data\":{\"credential\":\"" + SECRET + "\",\"credentialId\":\"cred-1\","
                            + "\"serverId\":\"01JTESTSERVERID0000000000\",\"userId\":\"u-1\",\"serverName\":\"Survival\"}}");
                }
            } else {
                send(exchange, 404, "{\"error\":{\"code\":\"not_found\",\"message\":\"no route\",\"requestId\":\"r\"}}");
            }
        } finally {
            exchange.close();
        }
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

    private String bodyOf(String requestLine) {
        for (String[] request : requests) {
            if (request[0].equals(requestLine)) {
                return request[1];
            }
        }
        return null;
    }

    private static boolean await(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    private boolean credentialWritten() {
        CredentialStore.ReadResult result = CredentialStore.read();
        return result.isParsed() && result.data().containsKey("cloud_credential");
    }

    @Test
    @DisplayName("login prints the code block, polls through authorization_pending and writes the ucs_ credential")
    void deviceLoginLinksTheServerAndWritesTheCredential() throws Exception {
        ConsoleCommandSender sender = mock(ConsoleCommandSender.class);
        doAnswer(invocation -> {
            senderLines.add(invocation.getArgument(0));
            return null;
        }).when(sender).sendMessage(anyString());

        new CloudLoginCommand().login(sender);

        assertThat(await(() -> bodyOf("POST /v1/device/authorize") != null, 5000))
                .as("the login must send POST /v1/device/authorize to UltiCloud; requests seen: %s",
                        requestLines())
                .isTrue();
        JsonObject authorize = JsonParser.parseString(bodyOf("POST /v1/device/authorize")).getAsJsonObject();
        assertThat(authorize.get("clientKind").getAsString()).isEqualTo("server");
        assertThat(authorize.get("protocol").getAsInt()).isEqualTo(2);
        assertThat(authorize.get("framework").getAsString()).isNotEmpty();
        assertThat(authorize.has("legacyServerUuid")).as("authorize body %s", authorize).isTrue();
        assertThat(authorize.get("legacyServerUuid").getAsString()).isEqualTo(STORED_UUID);
        assertThat(authorize.has("attachToServerId")).isFalse();

        assertThat(await(this::credentialWritten, 10_000))
                .as("the granted credential must reach credentials.json; requests seen: %s", requestLines())
                .isTrue();

        assertThat(bodyOf("POST /v1/device/token")).isEqualTo("{\"deviceCode\":\"" + DEVICE_CODE + "\"}");
        assertThat(String.join("\n", senderLines))
                .contains(VERIFICATION_URI)
                .contains(USER_CODE)
                .contains(COMPLETE_URI);

        Map<String, Object> document = CredentialStore.read().data();
        assertThat(document.get("uuid")).as("the install id is left byte-identical").isEqualTo(STORED_UUID);
        assertThat(document).as("a successful login removes the 6.3.0 user token").doesNotContainKey("cloud_token");
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) document.get("cloud_credential");
        assertThat(entry).containsEntry("credential", SECRET)
                .containsEntry("credentialId", "cred-1")
                .containsEntry("serverId", "01JTESTSERVERID0000000000")
                .containsEntry("userId", "u-1")
                .containsEntry("serverName", "Survival")
                .containsKey("linkedAt");

        assertThat(await(() -> CloudAuthManager.status().isLinked(), 5000)).isTrue();
        assertThat(CloudAuthManager.status().getServerName()).isEqualTo("Survival");

        for (String line : logLines) {
            assertThat(line).doesNotContain(SECRET).doesNotContain(DEVICE_CODE);
        }
        for (String line : senderLines) {
            assertThat(line).doesNotContain(SECRET).doesNotContain(DEVICE_CODE);
        }
    }

    private String requestLines() {
        StringBuilder builder = new StringBuilder();
        for (String[] request : requests) {
            builder.append(request[0]).append("; ");
        }
        return builder.toString();
    }
}
