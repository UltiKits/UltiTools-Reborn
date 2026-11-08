package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.command.ConsoleCommandSender;
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

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.commands.CloudLoginCommand;
import com.ultikits.ultitools.stub.StubAccessLog;
import com.ultikits.ultitools.stub.StubDeviceAuthority;
import com.ultikits.ultitools.stub.UltiCloudStub;

/**
 * UltiCloud "revoke and re-pair" from the plugin's side (issue #869), end to end against the in-process UltiCloud
 * stub: the owner's repair revokes this server's credential and closes the connection with 4401; the plugin deletes
 * the saved credential and names both {@code /ulticloud login <code>} and {@code /ulticloud login}; the operator runs
 * {@code /ulticloud login <code>}, which exchanges the already approved code through the unchanged
 * {@code POST /v1/device/token} -- no {@code POST /v1/device/authorize} -- stores the new credential for the
 * <b>same</b> server record and reconnects. A used or unknown code answers {@code expired_token} and is reported
 * plainly; a malformed one never leaves the server. The code is a secret: no log record and no sender line carries
 * it.
 */
@DisplayName("Re-pair code login: 4401, /ulticloud login <code>, same server record")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the test re-publishes the UltiTools singleton
class RepairCodeLoginEndToEndTest {

    private static final String SERVER_ID = InboundWiringTest.SERVER_ID;
    private static final String OLD_CREDENTIAL = "ucs_TESTSECRET";
    /** The request line of a token poll; the response line ({@code HTTP-RESPONSE <status> POST ...}) is not counted. */
    private static final String TOKEN = "HTTP POST " + StubDeviceAuthority.TOKEN_PATH;
    private static final String AUTHORIZE = "HTTP POST " + StubDeviceAuthority.AUTHORIZE_PATH;
    private static final String REJECTED = "UltiCloud rejected this server's credential (it was revoked or is no "
            + "longer valid). The saved credential was deleted. If you used \"Revoke and re-pair\" in the panel, run "
            + "/ulticloud login <code> with the code it shows; otherwise run /ulticloud login to link this server again.";
    private static final String EXPIRED = "UltiCloud: this re-pair code has expired or was already used. Create a "
            + "new one in the panel (server settings > Revoke and re-pair), or run /ulticloud login without a code.";

    @TempDir
    Path temp;

    private ServerMock server;
    private UltiCloudStub.Handle stub;
    private CloudFeatures features;
    private final YamlConfiguration config = new YamlConfiguration();
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final List<String> senderLines = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        Path state = temp.resolve("server").resolve(".ultikits");
        Files.createDirectories(state);
        Logger logger = Logger.getLogger("RepairCodeLoginEndToEndTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        TestHelper.mockUltiToolsInstance(plugin -> {
            lenient().when(plugin.getLogger()).thenReturn(logger);
            lenient().when(plugin.getConfig()).thenReturn(config);
            lenient().when(plugin.getDescription()).thenReturn(new PluginDescriptionFile(
                    "UltiTools", "6.4.0-SNAPSHOT", "com.ultikits.ultitools.UltiTools"));
        });
        CloudTestAccess.useTemporaryCredentials(state);
        stub = UltiCloudStub.start(UltiCloudStub.Options.defaults().accessLog(temp.resolve("access.log"))
                .acceptCredential(OLD_CREDENTIAL, SERVER_ID));
        CloudSession.setAgentEndpointOverrideForTesting(new URI("ws://127.0.0.1:" + stub.httpPort() + "/v1/agent"));
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

    private void connect() throws Exception {
        features = CloudFeatures.start(CloudFeatures.Bindings.forTesting());
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        PluginInitiationUtils.initWebsocket();
        await(() -> CloudAuthManager.status().isConnected(), "connected with the old credential");
    }

    /** The owner's "Revoke and re-pair": revoke, return the code, close the live connection with 4401. */
    private String repair() throws Exception {
        String code = stub.deviceAuthority().issueRepairCode(SERVER_ID, "Survival");
        stub.closeLive(4401, "credential revoked");
        await(() -> warnings().contains(REJECTED), "the 4401 notice");
        await(() -> !CloudAuthManager.status().isLinked(), "the saved credential deleted");
        return code;
    }

    private ConsoleCommandSender console() {
        ConsoleCommandSender sender = mock(ConsoleCommandSender.class);
        doAnswer(call -> {
            senderLines.add(call.getArgument(0));
            return null;
        }).when(sender).sendMessage(anyString());
        return sender;
    }

    @Test
    @DisplayName("a fresh code reattaches the same server record with a new credential and reconnects")
    void freshCodeReattachesTheSameServer() throws Exception {
        connect();
        String code = repair();
        assertThat(CredentialStore.read().data()).doesNotContainKey(ServerCredentialStore.KEY);

        int upgradesBefore = stub.accessLog().count("-> 101");
        new CloudLoginCommand().login(console(), code);

        await(() -> CloudAuthManager.status().isLinked(), "the new credential saved");
        ServerCredential stored = ServerCredentialStore.load();
        assertThat(stored.getServerId()).isEqualTo(SERVER_ID);
        assertThat(stored.getServerName()).isEqualTo("Survival");
        assertThat(stored.bearerValue()).startsWith("ucs_").isNotEqualTo(OLD_CREDENTIAL);
        await(() -> stub.accessLog().count("-> 101") > upgradesBefore, "the reconnect");
        await(() -> CloudAuthManager.status().isConnected(), "connected with the new credential");
        String fresh = StubAccessLog.fingerprint(stored.bearerValue());
        assertThat(stub.accessLog().lines()).anySatisfy(line ->
                assertThat(line).contains("UPGRADE").contains("-> 101").contains(fresh));
        assertThat(stub.credentials().serverIdOf(OLD_CREDENTIAL)).as("the old credential stays revoked").isNull();

        assertThat(stub.accessLog().count(TOKEN)).isGreaterThanOrEqualTo(1);
        assertThat(stub.accessLog().count(AUTHORIZE)).as("no new device authorization").isZero();
        assertThat(String.join("\n", senderLines))
                .contains(ChatColor.AQUA + "Redeeming the re-pair code with UltiCloud...");
        assertThat(infos()).contains("UltiCloud: this server is now linked as Survival (server ID " + SERVER_ID + ").");
        assertCodeNeverShown(code);
    }

    @Test
    @DisplayName("a used code is refused with expired_token, reported plainly, and nothing is saved")
    void usedCodeIsRefused() throws Exception {
        connect();
        String code = repair();
        new CloudLoginCommand().login(console(), code);
        await(() -> CloudAuthManager.status().isConnected(), "linked with the code");

        List<CloudAuthManager.LogoutOutcome> outcomes = new CopyOnWriteArrayList<>();
        CloudAuthManager.logoutReporting(outcomes::add);
        server.getScheduler().performOneTick();
        await(() -> !outcomes.isEmpty(), "the logout outcome");
        server.getScheduler().waitAsyncTasksFinished();
        ApiRateLimiter.resetAll();

        int tokenPolls = stub.accessLog().count(TOKEN);
        new CloudLoginCommand().login(console(), code);

        await(() -> warnings().contains(EXPIRED), "the expired notice");
        assertThat(stub.accessLog().count(TOKEN)).as("exactly one more poll").isEqualTo(tokenPolls + 1);
        assertThat(CloudAuthManager.status().isLinked()).isFalse();
        assertThat(CredentialStore.read().data()).doesNotContainKey(ServerCredentialStore.KEY);
        assertCodeNeverShown(code);
    }

    @Test
    @DisplayName("a code UltiCloud never issued is refused with the same expired notice")
    void unknownCodeIsRefused() throws Exception {
        String unknown = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE";
        assertThat(ServerCredentialStore.delete()).isTrue();
        new CloudLoginCommand().login(console(), unknown);

        await(() -> warnings().contains(EXPIRED), "the expired notice");
        assertThat(CloudAuthManager.status().isLinked()).isFalse();
        assertCodeNeverShown(unknown);
    }

    @Test
    @DisplayName("a malformed code never leaves the server and is not echoed back")
    void malformedCodeIsRefusedLocally() throws Exception {
        assertThat(ServerCredentialStore.delete()).isTrue();
        String malformed = "not a code!";

        new CloudLoginCommand().login(console(), malformed);

        assertThat(senderLines).containsExactly(ChatColor.RED + "That is not a valid UltiCloud re-pair code. "
                + "Copy the whole code from the panel and try again.");
        assertThat(stub.accessLog().count(TOKEN)).isZero();
        assertThat(stub.accessLog().count(AUTHORIZE)).isZero();
        assertCodeNeverShown(malformed);
    }

    @Test
    @DisplayName("a linked server refuses the code like a plain login, and the code is not sent")
    void linkedServerRefusesTheCode() throws Exception {
        String code = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE";

        new CloudLoginCommand().login(console(), code);

        assertThat(senderLines).containsExactly(ChatColor.YELLOW
                + "This server is already linked to UltiCloud as Survival. Use /ulticloud logout first to link it again.");
        assertThat(stub.accessLog().count(TOKEN)).isZero();
    }

    private void assertCodeNeverShown(String code) {
        assertThat(senderLines).noneSatisfy(line -> assertThat(line).contains(code));
        List<String> messages = new ArrayList<>();
        for (LogRecord record : records) {
            messages.add(String.valueOf(record.getMessage()));
        }
        assertThat(messages).noneSatisfy(line -> assertThat(line).contains(code));
    }

    private List<String> warnings() {
        return messages(Level.WARNING);
    }

    private List<String> infos() {
        return messages(Level.INFO);
    }

    private List<String> messages(Level level) {
        List<String> messages = new ArrayList<>();
        for (LogRecord record : records) {
            if (record.getLevel() == level) {
                messages.add(record.getMessage());
            }
        }
        return messages;
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }
}
