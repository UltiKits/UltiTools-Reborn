package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.stub.UltiCloudStub;
import com.ultikits.ultitools.websocket.CloudPlayerLinks;

/**
 * The tracer of plan 22-21 (D-26, S-59, S-60, UltiLogin R2/R3): a module registers its player-link
 * handler through {@link UltiTools#getCloudPlayerLinks()} while it enables, a link is created over a
 * real connection to the in-process UltiCloud stub, and the completion is redelivered after two
 * simulated server restarts. The handler is asked once; its result is in {@code cloud-outbox.json}
 * before the gateway receives it, and the second redelivery is answered from that record.
 */
@DisplayName("Player link live: handler at startup, completion redelivered across restarts")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the restart re-publishes the UltiTools singleton
class PlayerLinkLiveTest {

    static final String SERVER_ID = "01JTESTSERVER0000000000000";
    private static final String CREDENTIAL = "ucs_TESTSECRET";
    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @TempDir
    Path temp;

    private UltiCloudStub.Handle stub;
    private CloudFeatures features;
    private final AtomicInteger handlerCalls = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        Path state = temp.resolve("server").resolve(".ultikits");
        Files.createDirectories(state);
        CloudTestAccess.useTemporaryCredentials(state);
        stub = UltiCloudStub.start(UltiCloudStub.Options.defaults().accessLog(temp.resolve("access.log"))
                .acceptCredential(CREDENTIAL, SERVER_ID));
        CloudSession.setAgentEndpointOverrideForTesting(
                new java.net.URI("ws://127.0.0.1:" + stub.httpPort() + "/v1/agent"));
        CloudTestAccess.storeLinkedCredential("Survival", SERVER_ID);
    }

    @AfterEach
    void tearDown() throws Exception {
        stopServer();
        stub.close();
        CloudTestAccess.reset();
        Field instance = UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test
    @DisplayName("create, restart, completed redelivered twice: one handler call, the persisted receipt replayed")
    void completionSurvivesRestarts() throws Exception {
        // Run 1: the module registers at startup; a link is created and answered.
        UltiToolsPlugin module = startServer();
        CloudPlayerLinks links = UltiTools.getInstance().getCloudPlayerLinks();
        CompletableFuture<CloudPlayerLinks.LinkCreation> creation = links.create(module, PLAYER, "Steve",
                CloudPlayerLinks.GameAuth.none(), EnumSet.of(CloudPlayerLinks.LinkProof.WEB_REGISTRATION));
        List<JsonObject> creates = stub.gateway().awaitReceived("player.link.create", 1, 10_000);
        assertThat(creates).hasSize(1);
        String createId = creates.get(0).get("id").getAsString();
        stub.gateway().send("player.link.created", "url", null, null, createId);
        CloudPlayerLinks.LinkCreation created = creation.get(10, TimeUnit.SECONDS);
        assertThat(created.failure()).isNull();
        assertThat(created.url()).isEqualTo("https://panel.ultikits.com/link#ucl_3q2f9XbK");

        // Run 2: restart; the completion arrives with no create pending in this run.
        stopServer();
        startServer();
        String completedId = UltiCloudStub.Contract.newUlid();
        CloudStateStore store = CloudSession.current().cloudStateStore();
        CommandDedupeSet dedupe = CloudSession.current().commandDedupe();
        AtomicInteger resultsAtReceiptWrite = new AtomicInteger(-1);
        store.setBeforeMoveHookForTesting(() -> {
            if (dedupe.receiptFor(completedId) != null && resultsAtReceiptWrite.get() < 0) {
                resultsAtReceiptWrite.set(stub.gateway().received("player.link.completed.result").size());
            }
        });
        stub.gateway().send("player.link.completed", "web-registration", null, completedId, createId);
        List<JsonObject> first = stub.gateway().awaitReceived("player.link.completed.result", 1, 10_000);
        assertThat(first).hasSize(1);
        assertThat(handlerCalls.get()).isEqualTo(1);
        assertThat(resultsAtReceiptWrite.get()).as("receipt persisted before the result reached the gateway")
                .isZero();
        assertThat(first.get(0).get("replyTo").getAsString()).isEqualTo(completedId);
        JsonObject firstPayload = first.get(0).getAsJsonObject("payload");
        assertThat(firstPayload.get("status").getAsString()).isEqualTo("ok");
        assertThat(receiptInFile(completedId)).isEqualTo(firstPayload);

        // Run 3: another restart; the same completion is answered from the record.
        stopServer();
        startServer();
        stub.gateway().send("player.link.completed", "web-registration", null, completedId, createId);
        List<JsonObject> both = stub.gateway().awaitReceived("player.link.completed.result", 2, 10_000);
        assertThat(both).hasSize(2);
        assertThat(both.get(1).getAsJsonObject("payload")).isEqualTo(firstPayload);
        assertThat(both.get(1).get("replyTo").getAsString()).isEqualTo(completedId);
        Thread.sleep(200);
        assertThat(handlerCalls.get()).isEqualTo(1);
        assertThat(stub.accessLog().count("WS-INVALID")).isZero();
    }

    @Test
    @DisplayName("the API exists before any connection and answers NOT_CONNECTED while offline")
    void offlineCreateIsNotConnected() throws Exception {
        UltiTools plugin = publishUltiTools();
        features = CloudFeatures.start(CloudFeatures.Bindings.forTesting());
        setFeatures(plugin, features);
        CloudPlayerLinks links = UltiTools.getInstance().getCloudPlayerLinks();
        assertThat(links).isNotNull().isSameAs(features.playerLinks());
        CloudPlayerLinks.LinkCreation result = links.create(mock(UltiToolsPlugin.class), PLAYER, "Steve",
                CloudPlayerLinks.GameAuth.none(), EnumSet.of(CloudPlayerLinks.LinkProof.IN_GAME_SESSION))
                .get(5, TimeUnit.SECONDS);
        assertThat(result.failure()).isEqualTo(CloudPlayerLinks.Failure.NOT_CONNECTED);
    }

    /** One simulated server start: framework, features, the module's enable, then the connection. */
    private UltiToolsPlugin startServer() throws Exception {
        UltiTools plugin = publishUltiTools();
        features = CloudFeatures.start(CloudFeatures.Bindings.forTesting());
        setFeatures(plugin, features);
        CloudSession.resetForTesting();
        CloudSession.setAgentEndpointOverrideForTesting(
                new java.net.URI("ws://127.0.0.1:" + stub.httpPort() + "/v1/agent"));
        UltiToolsPlugin module = mock(UltiToolsPlugin.class);
        UltiTools.getInstance().getCloudPlayerLinks().setHandler(module, completion -> {
            handlerCalls.incrementAndGet();
            return CompletableFuture.completedFuture(CloudPlayerLinks.GameAccountResult.CREATED);
        });
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        PluginInitiationUtils.initWebsocket();
        await(() -> CloudAuthManager.status().isConnected(), "connected");
        await(() -> stub.gateway().openConnections() == 1, "one live connection");
        return module;
    }

    /** One simulated server stop: the session flushes and closes, the features stop. */
    private void stopServer() {
        CloudSession.current().invalidate();
        if (features != null) {
            features.shutdown();
            features = null;
        }
    }

    private JsonObject receiptInFile(String id) throws Exception {
        Path file = temp.resolve("server").resolve(".ultikits").resolve(CloudStateStore.FILE_NAME);
        JsonObject root = JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .getAsJsonObject();
        for (JsonElement element : root.getAsJsonArray("commandIds")) {
            JsonObject entry = element.getAsJsonObject();
            if (id.equals(entry.get("id").getAsString())) {
                return entry.getAsJsonObject("receipt");
            }
        }
        return null;
    }

    private static UltiTools publishUltiTools() {
        Logger logger = Logger.getLogger("PlayerLinkLiveTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { }
            @Override public void flush() { }
            @Override public void close() { }
        });
        AtomicReference<UltiTools> published = new AtomicReference<>();
        TestHelper.mockUltiToolsInstance(plugin -> {
            lenient().when(plugin.getLogger()).thenReturn(logger);
            lenient().when(plugin.getDescription()).thenReturn(new PluginDescriptionFile(
                    "UltiTools", "6.3.1-SNAPSHOT", "com.ultikits.ultitools.UltiTools"));
            lenient().when(plugin.getCloudPlayerLinks()).thenCallRealMethod();
            published.set(plugin);
        });
        return published.get();
    }

    private static void setFeatures(UltiTools plugin, CloudFeatures value) throws Exception {
        Field field = UltiTools.class.getDeclaredField("cloudFeatures");
        field.setAccessible(true);
        field.set(plugin, value);
        Mockito.clearInvocations(plugin);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 30_000L;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }
}
