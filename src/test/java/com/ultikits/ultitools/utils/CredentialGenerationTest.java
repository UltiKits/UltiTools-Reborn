package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.UltiTools;

@DisplayName("v2 凭据提交与会话身份")
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class CredentialGenerationTest {
    @TempDir
    Path tempDir;
    private Path file;
    private final CloudReconnectStateMachineTest.AgentRuntime runtime =
            new CloudReconnectStateMachineTest.AgentRuntime();

    @BeforeEach
    void setUp() throws Exception {
        TestHelper.mockUltiToolsInstance(plugin -> lenient().when(plugin.getLogger()).thenReturn(mock(Logger.class)));
        CloudSession.resetForTesting();
        file = tempDir.resolve("credentials.json");
        CredentialStore.setTargetPathForTesting(file);
        CredentialStore.setOldLocationForTesting(tempDir.resolve("absent.json"));
        Files.write(file, "{\"uuid\":\"0123456789abcdef0123456789abcdef\"}".getBytes(StandardCharsets.UTF_8));
        runtime.install();
    }

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void tearDown() throws Exception {
        CloudSession.current().invalidate();
        CloudSession.resetForTesting();
        CredentialStore.setSimulateWriteFailureForTesting(false);
        CredentialStore.clearTargetPathForTesting();
        CredentialStore.clearOldLocationForTesting();
        Field instance = UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test
    void commitSucceedsOnACurrentSession() {
        CloudSession session = CloudSession.current();
        assertThat(session.commitCredential(credential("OLD"))).isTrue();
        assertThat(session.getCredential().getCredentialId()).isEqualTo("cred-OLD");
        assertThat(ServerCredentialStore.load().getCredentialId()).isEqualTo("cred-OLD");
        assertThat(CredentialStore.read().data()).containsEntry("uuid", "0123456789abcdef0123456789abcdef");
    }

    @Test
    void commitIsRejectedAfterInvalidation() throws Exception {
        CloudSession session = CloudSession.current();
        byte[] before = Files.readAllBytes(file);
        session.invalidate();
        assertThat(session.commitCredential(credential("LATE"))).isFalse();
        assertThat(session.getCredential()).isNull();
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    void multipleInvalidationsNeverRevalidateASession() {
        CloudSession session = CloudSession.current();
        session.invalidate();
        session.invalidate();
        assertThat(session.isCurrent()).isFalse();
        assertThat(session.commitCredential(credential("LATE"))).isFalse();
    }

    @Test
    void freshSessionAcceptsInvalidateWithoutThrowing() {
        CloudSession session = CloudSession.current();
        assertThatCode(session::invalidate).doesNotThrowAnyException();
        assertThat(session.connectionState()).isEqualTo(AgentConnection.State.STOPPED);
    }

    @Test
    void sessionInvalidatedBeforeAnyWorkStillReportsInvalidAndRefusesCommit() {
        CloudSession session = CloudSession.current();
        session.invalidate();
        assertThat(session.commitCredential(credential("LATE"))).isFalse();
        session.startConnection();
        assertThat(runtime.schedulers).isEmpty();
    }

    @Test
    void pollCompletionAfterLogoutWithoutExplicitStop_commitRejected() throws Exception {
        CloudSession beforeLogout = CloudSession.current();
        byte[] before = Files.readAllBytes(file);
        CloudAuthManager.logout();
        assertThat(beforeLogout.commitCredential(credential("LATE"))).isFalse();
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    void loginAfterTeardownCompletes_isNotClearedByLogout() throws Exception {
        CloudAuthManager.logout();
        CloudSession fresh = CloudSession.startNew();
        assertThat(fresh.commitCredential(credential("NEW"))).isTrue();
        assertThat(ServerCredentialStore.load().getCredentialId()).isEqualTo("cred-NEW");
    }

    @Test
    void sessionCapturedBeforeBlockingCall_isHonoredNotReReadMidFlight() throws Exception {
        CloudSession old = CloudSession.current();
        CloudSession fresh = CloudSession.startNew();
        assertThat(fresh.commitCredential(credential("NEW"))).isTrue();
        byte[] before = Files.readAllBytes(file);
        assertThat(old.commitCredential(credential("LATE"))).isFalse();
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void supersededButNotYetInvalidatedSessionCannotCommit() throws Exception {
        CloudSession old = CloudSession.current();
        Field current = CloudSession.class.getDeclaredField("current");
        current.setAccessible(true);
        CloudSession fresh = new CloudSession();
        current.set(null, fresh);
        try {
            assertThat(old.isCurrent()).isTrue();
            assertThat(fresh.commitCredential(credential("NEW"))).isTrue();
            byte[] before = Files.readAllBytes(file);
            assertThat(old.commitCredential(credential("LATE"))).isFalse();
            assertThat(Files.readAllBytes(file)).isEqualTo(before);
        } finally {
            old.invalidate();
        }
    }

    @Test
    void logoutBetweenCommitAndActivationStart_abortsActivation() throws Exception {
        CloudSession session = CloudSession.current();
        session.commitCredential(credential("OLD"));
        session.invalidate();
        byte[] before = Files.readAllBytes(file);
        session.startConnection();
        assertThat(runtime.schedulers).isEmpty();
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    void teardownOrderDoesNotAffectTheOutcome() throws Exception {
        List<List<String>> orders = Arrays.asList(Arrays.asList("poll", "agent", "credential"),
                Arrays.asList("credential", "agent", "poll"), Arrays.asList("agent", "poll", "credential"));
        for (List<String> order : orders) {
            CloudSession session = CloudSession.startNew();
            session.commitCredential(credential("OLD"));
            session.startConnection();
            runtime.runDue();
            session.markInvalidatedForTesting();
            for (String step : order) {
                if ("poll".equals(step)) { session.stopPolling(); }
                if ("agent".equals(step)) { session.stopConnection(); }
                if ("credential".equals(step)) { ServerCredentialStore.deleteIfMatches("cred-OLD"); }
                byte[] before = Files.readAllBytes(file);
                assertThat(session.commitCredential(credential("LATE"))).as("ordering %s", order).isFalse();
                assertThat(Files.readAllBytes(file)).isEqualTo(before);
            }
            assertThat(ServerCredentialStore.load()).isNull();
            assertThat(runtime.scheduler().isShutdown()).isTrue();
            assertThat(session.connectionState()).isEqualTo(AgentConnection.State.STOPPED);
        }
    }

    @Test
    void invalidatedLinkedSessionReportsNotConnectedWithoutDeletingCredential() {
        CloudSession session = CloudSession.current();
        session.commitCredential(credential("OLD"));
        session.startConnection();
        runtime.runDue();
        runtime.last().accept();
        assertThat(CloudAuthManager.status().isConnected()).isTrue();
        session.invalidate();
        assertThat(CloudAuthManager.status().isConnected()).isFalse();
        assertThat(CloudAuthManager.status().isLinked()).isTrue();
        assertThat(ServerCredentialStore.load()).isNotNull();
    }

    @Test
    void compareAndDeleteOnlyRemovesMatchingCredential() {
        ServerCredentialStore.save(credential("NEW"));
        assertThat(ServerCredentialStore.deleteIfMatches("cred-OLD")).isFalse();
        assertThat(ServerCredentialStore.load().getCredentialId()).isEqualTo("cred-NEW");
        assertThat(ServerCredentialStore.deleteIfMatches("cred-NEW")).isTrue();
        assertThat(ServerCredentialStore.load()).isNull();
        assertThat(CredentialStore.read().data()).containsEntry("uuid", "0123456789abcdef0123456789abcdef");
    }

    private static ServerCredential credential(String suffix) {
        return CloudReconnectStateMachineTest.credential(suffix);
    }
}
