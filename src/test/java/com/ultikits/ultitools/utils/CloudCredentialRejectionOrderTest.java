package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;

class CloudCredentialRejectionOrderTest {
    @TempDir
    Path tempDir;

    private final Logger logger = Logger.getAnonymousLogger();
    private final CloudReconnectStateMachineTest.AgentRuntime runtime =
            new CloudReconnectStateMachineTest.AgentRuntime();
    private MockedStatic<UltiTools> pluginSingleton;
    private CloudSession session;

    @BeforeEach
    void setUp() {
        CloudTestAccess.useTemporaryCredentials(tempDir);
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        UltiTools plugin = mock(UltiTools.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getDescription()).thenReturn(new PluginDescriptionFile(
                "UltiTools", "7.0.0", UltiTools.class.getName()));
        pluginSingleton = mockStatic(UltiTools.class, CALLS_REAL_METHODS);
        pluginSingleton.when(UltiTools::getInstance).thenReturn(plugin);
        runtime.install();
        var clock = new DeviceLoginTestSupport.FakeClock();
        // Exercise the real login entry point without running the queued device request.
        CloudSession.setDeviceSeamsForTesting(null, new DeviceLoginTestSupport.FakeScheduler(clock), clock);
        session = CloudSession.current();
        assertThat(session.commitCredential(credential("Rejected"))).isTrue();
        assertThat(session.cloudOutbox()).isNotNull();
        assertThat(session.commandDedupe()).isNotNull();
        session.startConnection();
        runtime.runDue();
    }

    @AfterEach
    void tearDown() {
        try {
            CloudTestAccess.reset();
        } finally {
            if (pluginSingleton != null) {
                pluginSingleton.close();
            }
        }
    }

    @Test
    void deletedCredentialDoesNotBlockLoginAtTheNotice() {
        assertRejectionNotice("The saved credential was deleted.", "requesting");
        assertThat(ServerCredentialStore.load()).isNull();
    }

    @Test
    void replacementOnDiskIsTheOnlyCredentialLoginSeesAtTheNotice() {
        ServerCredential replacement = credential("Replacement");
        ServerCredentialStore.save(replacement);

        assertRejectionNotice("holds a different credential, so it was kept.", "alreadyLinked:Replacement");
        assertThat(ServerCredentialStore.load().getCredentialId()).isEqualTo(replacement.getCredentialId());
    }

    @Test
    void failedDeletionStillClearsMemoryBeforeTheNotice() throws Exception {
        Path stored = tempDir.resolve(CredentialStore.CREDENTIAL_FILE_NAME);
        String unreadable = "{ invalid JSON";
        Files.writeString(stored, unreadable);

        assertRejectionNotice("the saved credential could not be deleted.", "requesting");
        assertThat(Files.readString(stored)).isEqualTo(unreadable);
    }

    private void assertRejectionNotice(String messageFragment, String expectedLoginEvent) {
        List<NoticeState> observed = new ArrayList<>();
        var login = new DeviceLoginTestSupport.RecordingListener();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (Level.WARNING.equals(record.getLevel()) && record.getMessage().contains(messageFragment)) {
                    // Capture the state inside the publication, before login can replace the session.
                    observed.add(new NoticeState(Thread.currentThread(), session.hasCredential(),
                            session.cloudOutbox() != null || session.commandDedupe() != null
                                    || session.cloudStateStore() != null));
                    CloudAuthManager.login(login);
                }
            }
            @Override public void flush() {
                // Nothing is buffered: each record is inspected as it is published.
            }
            @Override public void close() {
                // No resource to release.
            }
        };
        logger.addHandler(handler);
        try {
            runtime.last().close(4401);
        } finally {
            logger.removeHandler(handler);
        }
        assertAll(
                () -> assertThat(observed).as("session state at the one rejection notice")
                        .containsExactly(new NoticeState(Thread.currentThread(), false, false)),
                () -> assertThat(login.events).as("login response during the rejection notice")
                        .containsExactly(expectedLoginEvent));
    }

    private static ServerCredential credential(String name) {
        return new ServerCredential("ucs_TEST_" + name, "cred-" + name, HandshakeEndToEndTest.SERVER_ID, 43L, name, 1L);
    }

    private record NoticeState(Thread publisher, boolean hasCredential, boolean hasBufferedState) { }
}
