package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.stub.StubAccessLog;
import com.ultikits.ultitools.stub.UltiCloudStub;

/**
 * Plan 22-21 fix round, gap 1 (D-09, D-10): the running session is the one owner of the current server credential.
 * A {@code credential.rotate} goes through it, so its in-memory credential and {@code credentials.json} change in one
 * step; the next connection and {@code /ulticloud logout} both read that owner. Before the fix the rotation wrote only
 * the file: logout then revoked the retired credential (the gateway answered 401, reported as revoked) and its
 * delete-if-matches found a different id on disk, so the rotated credential stayed valid and on disk.
 */
@DisplayName("Credential rotation: one owner for the next connection and the logout")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the test re-publishes the UltiTools singleton
class CredentialRotationOwnerTest {

    private static final String SERVER_ID = InboundWiringTest.SERVER_ID;
    private static final String CREDENTIAL = "ucs_TESTSECRET";
    /** The secret and id of the contract fixture {@code credential.rotate/valid/default-grace.json}. */
    private static final String ROTATED = "ucs_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String ROTATED_ID = "01JB2Z8Y3K4M5N6P7Q8R9S0CRD";
    private static final String REVOKE = "POST /v1/agent/credential/revoke";

    @TempDir
    Path temp;

    private ServerMock server;
    private UltiCloudStub.Handle stub;
    private CloudFeatures features;
    private Path credentialFile;
    private final YamlConfiguration config = new YamlConfiguration();

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        Path state = temp.resolve("server").resolve(".ultikits");
        Files.createDirectories(state);
        credentialFile = state.resolve("credentials.json");
        Logger logger = Logger.getLogger("CredentialRotationOwnerTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { }
            @Override public void flush() { }
            @Override public void close() { }
        });
        TestHelper.mockUltiToolsInstance(plugin -> {
            lenient().when(plugin.getLogger()).thenReturn(logger);
            lenient().when(plugin.getConfig()).thenReturn(config);
            lenient().when(plugin.getDescription()).thenReturn(new PluginDescriptionFile(
                    "UltiTools", "6.3.1-SNAPSHOT", "com.ultikits.ultitools.UltiTools"));
        });
        CloudTestAccess.useTemporaryCredentials(state);
        stub = UltiCloudStub.start(UltiCloudStub.Options.defaults().accessLog(temp.resolve("access.log"))
                .acceptCredential(CREDENTIAL, SERVER_ID));
        CloudSession.setAgentEndpointOverrideForTesting(new URI("ws://127.0.0.1:" + stub.httpPort() + "/v1/agent"));
        // The logout's revoke goes to the stub's REST front door.
        HttpRequestUtils.setBaseUrlForTesting("http://127.0.0.1:" + stub.httpPort());
        CloudTestAccess.storeLinkedCredential("Survival", SERVER_ID);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.getScheduler().waitAsyncTasksFinished();
        CloudSession.current().invalidate();
        if (features != null) {
            features.shutdown();
        }
        stub.close();
        CloudTestAccess.reset();
        MockBukkitHelper.safeUnmock();
        Field instance = UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private void connectAndRotate() throws Exception {
        features = CloudFeatures.start(CloudFeatures.Bindings.forTesting());
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        PluginInitiationUtils.initWebsocket();
        await(() -> CloudAuthManager.status().isConnected(), "connected");
        String id = stub.gateway().send("credential.rotate", "default-grace", null, null, null)
                .get("id").getAsString();
        await(() -> !stub.gateway().received("credential.rotate.ack").isEmpty(), "the rotation ack");
        JsonObject ack = stub.gateway().received("credential.rotate.ack").get(0);
        assertThat(ack.get("replyTo").getAsString()).isEqualTo(id);
    }

    @Test
    @DisplayName("logout after a rotation revokes the rotated credential and removes it from disk")
    void logoutAfterRotationRevokesTheRotatedCredential() throws Exception {
        connectAndRotate();
        List<CloudAuthManager.LogoutOutcome> outcomes = new CopyOnWriteArrayList<>();
        int revokesBefore = stub.accessLog().count(REVOKE);

        CloudAuthManager.logoutReporting(outcomes::add);
        server.getScheduler().performOneTick();
        await(() -> !outcomes.isEmpty(), "the logout outcome");
        server.getScheduler().waitAsyncTasksFinished();

        String rotated = StubAccessLog.fingerprint(ROTATED);
        String retired = StubAccessLog.fingerprint(CREDENTIAL);
        assertThat(stub.accessLog().count(REVOKE)).as("one revoke request").isGreaterThan(revokesBefore);
        assertThat(stub.accessLog().lines()).as("the revoke carries the rotated bearer")
                .anySatisfy(line -> assertThat(line).contains("HTTP " + REVOKE).contains(rotated))
                .noneSatisfy(line -> assertThat(line).contains("HTTP " + REVOKE).contains(retired));
        assertThat(stub.accessLog().lines()).as("the gateway accepted the revoke")
                .anySatisfy(line -> assertThat(line).contains("HTTP-RESPONSE 200 " + REVOKE));
        assertThat(outcomes).containsExactly(CloudAuthManager.LogoutOutcome.REVOKED);
        assertThat(CredentialStore.read().data()).doesNotContainKey(ServerCredentialStore.KEY);
        assertThat(new String(Files.readAllBytes(credentialFile), java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain(ROTATED);
        assertThat(CloudSession.current().getCredential()).isNull();
        assertThat(CloudAuthManager.status().isLinked()).isFalse();
    }

    @Test
    @DisplayName("after a rotation the session holds the rotated credential and the reconnect authenticates with it")
    void reconnectAfterRotationUsesTheRotatedCredential() throws Exception {
        connectAndRotate();
        ServerCredential held = CloudSession.current().getCredential();
        assertThat(held.getCredentialId()).isEqualTo(ROTATED_ID);
        assertThat(held.bearerValue()).isEqualTo(ROTATED);
        assertThat(held.getServerId()).isEqualTo(SERVER_ID);
        assertThat(held.getServerName()).isEqualTo("Survival");
        assertThat(held.getUserId()).isEqualTo(43L);
        assertThat(ServerCredentialStore.load().getCredentialId()).isEqualTo(ROTATED_ID);
        assertThat(ServerCredentialStore.load().getUserId()).isEqualTo(43L);

        int upgradesBefore = stub.accessLog().count("-> 101");
        stub.closeLive(1001, "going away");
        await(() -> stub.accessLog().count("-> 101") > upgradesBefore, "the reconnect");
        String rotated = StubAccessLog.fingerprint(ROTATED);
        List<String> lines = stub.accessLog().lines();
        String lastUpgrade = null;
        for (String line : lines) {
            if (line.contains("UPGRADE") && line.contains("-> 101")) {
                lastUpgrade = line;
            }
        }
        assertThat(lastUpgrade).contains(rotated);
        await(() -> CloudAuthManager.status().isConnected(), "connected again");
    }

    @Test
    @DisplayName("a rotation reaching an invalidated session writes nothing and is not acknowledged")
    void rotationOnAnInvalidatedSessionWritesNothing() throws Exception {
        CloudSession session = CloudSession.current();
        assertThat(session.loadServerCredential()).isTrue();
        byte[] before = Files.readAllBytes(credentialFile);
        session.invalidate();
        assertThat(session.rotateCredential(ROTATED, ROTATED_ID)).isFalse();
        assertThat(Files.readAllBytes(credentialFile)).isEqualTo(before);
    }

    @Test
    @DisplayName("a rotation for a credential of another server identity writes nothing")
    void rotationForAnotherIdentityWritesNothing() throws Exception {
        CloudSession session = CloudSession.current();
        assertThat(session.loadServerCredential()).isTrue();
        // Another identity reached the file meanwhile (a hand edit): the session must not graft its rotation onto it.
        CloudTestAccess.storeLinkedCredential("Other", "01JOTHERSERVER000000000000");
        byte[] before = Files.readAllBytes(credentialFile);
        assertThat(session.rotateCredential(ROTATED, ROTATED_ID)).isFalse();
        assertThat(Files.readAllBytes(credentialFile)).isEqualTo(before);
        assertThat(session.getCredential().bearerValue()).isEqualTo(CREDENTIAL);
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
