package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.RecordingChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persist-then-ack behaviour of {@code credential.rotate}: a new credential is acknowledged only
 * after it is stored, a failed store sends nothing, and nothing anywhere carries the secret.
 */
@DisplayName("Credential rotation: persist, then acknowledge, without the secret reaching a log")
class CredentialRotationHandlerTest {

    private static final String ROTATED = "ucs_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private RecordingChannel recording;
    private AgentMessageRouter router;
    private List<String> persisted;
    private volatile boolean persistResult;
    private volatile Runnable duringPersist;
    private CapturedLogs logs;

    @BeforeEach
    void setUp() {
        recording = new RecordingChannel();
        persisted = new ArrayList<>();
        persistResult = true;
        router = new AgentMessageRouter(capability -> true, capability -> "not available");
        router.register("credential.rotate", new CredentialRotationHandler(new CredentialRotationHandler.CredentialPersister() {
            @Override
            public boolean persistRotated(String credential, String credentialId) {
                persisted.add(credentialId);
                if (duringPersist != null) {
                    duringPersist.run();
                }
                return persistResult;
            }
        }));
        logs = new CapturedLogs(Logger.getLogger(CredentialRotationHandler.class.getName()));
    }

    @AfterEach
    void tearDown() {
        logs.detach();
    }

    @Test
    @DisplayName("a valid rotation is persisted first and then acknowledged once")
    void rotationIsPersistedThenAcknowledged() {
        JsonObject frame = ContractFixtures.object("fixtures/agent/credential.rotate/valid/default-grace.json");
        duringPersist = () -> assertThat(recording.frames())
                .as("nothing may be acknowledged before the credential is stored").isEmpty();

        router.dispatch(frame.toString(), recording);

        assertThat(persisted).containsExactly("01JB2Z8Y3K4M5N6P7Q8R9S0CRD");
        assertThat(recording.frames()).hasSize(1);
        JsonObject ack = recording.frames().get(0);
        assertThat(ack.get("type").getAsString()).isEqualTo("credential.rotate.ack");
        assertThat(ack.get("replyTo").getAsString()).isEqualTo("01JB2Z8Y3K4M5N6P7Q8R9S0TVW");
        assertThat(ack.getAsJsonObject("payload").keySet()).isEmpty();
        assertThat(logs.messages()).isEmpty();
    }

    @Test
    @DisplayName("a failed store sends no acknowledgement and logs no secret")
    void failedStoreSendsNothing() {
        persistResult = false;

        router.dispatch(ContractFixtures.object("fixtures/agent/credential.rotate/valid/default-grace.json").toString(),
                recording);

        assertThat(persisted).hasSize(1);
        assertThat(recording.frames()).isEmpty();
        assertThat(logs.messages()).as("one warning, without the credential").hasSize(1);
        assertThat(logs.messages().get(0)).doesNotContain(ROTATED).doesNotContain("ucs_");
    }

    @Test
    @DisplayName("a redelivered rotation is persisted again and acknowledged again")
    void redeliveryPersistsAndAcknowledgesAgain() {
        JsonObject frame = ContractFixtures.object("fixtures/agent/credential.rotate/valid/default-grace.json");

        router.dispatch(frame.toString(), recording);
        router.dispatch(frame.toString(), recording);

        assertThat(persisted).hasSize(2);
        assertThat(recording.frames()).hasSize(2);
        assertThat(recording.frames().get(1).get("type").getAsString()).isEqualTo("credential.rotate.ack");
    }

    @Test
    @DisplayName("a CLI credential is refused as invalid_message and never reaches the store")
    void cliCredentialIsRefused() {
        JsonObject invalid = ContractFixtures.object(
                "fixtures/agent/credential.rotate/invalid/cli-credential.json").getAsJsonObject("frame");

        router.dispatch(invalid.toString(), recording);

        assertThat(persisted).isEmpty();
        assertThat(recording.frames()).hasSize(1);
        JsonObject error = recording.frames().get(0);
        assertThat(error.get("type").getAsString()).isEqualTo("error");
        assertThat(error.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("invalid_message");
    }

    /** Collects this logger's records so a test can prove what was and was not written. */
    private static final class CapturedLogs extends Handler {
        private final Logger logger;
        private final List<String> messages = new ArrayList<>();

        private CapturedLogs(Logger logger) {
            this.logger = logger;
            logger.addHandler(this);
            setLevel(Level.ALL);
        }

        @Override
        public void publish(LogRecord record) {
            messages.add(String.valueOf(record.getMessage()));
        }

        @Override
        public void flush() {
            // Nothing is buffered.
        }

        @Override
        public void close() {
            // The logger is detached in tearDown.
        }

        private List<String> messages() {
            return messages;
        }

        private void detach() {
            logger.removeHandler(this);
        }
    }
}
