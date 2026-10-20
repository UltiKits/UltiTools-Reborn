package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Structural invariant: {@code batchEnabled}, {@code batchSize} and {@code intervalMs} on
 * {@link UltiPanelLogTransmitter} must be {@code volatile} (Gate-2 finding, review round 12,
 * pull request #467).
 *
 * <p>These three fields are written from the panel's WebSocket receive thread ({@code
 * setBatchEnabled}/{@code setBatchSize}/{@code setIntervalMs}, reachable live over the panel's
 * {@code config} action) and read from two other threads that never coordinate with the writer
 * on any lock: {@code addToBatch}'s {@code logQueue.size() >= batchSize} check (any logging
 * thread) and {@code sendBatch}'s {@code for (int i = 0; i < batchSize ...)} loop (the batch
 * scheduler thread). Without {@code volatile}, the Java Memory Model gives a reader thread no
 * guarantee it will ever observe a value written on a different thread -- a live {@code
 * batchConfig.size: 2} update can be acknowledged as {@code config_updated} while the logging
 * thread keeps comparing against a stale {@code 10} indefinitely, silencing the very
 * size-threshold flush #432 exists to make live. Real-machine acceptance happening to observe
 * the new value promptly is exactly what makes the missing {@code volatile} dangerous here -- it
 * works until the JIT/CPU cache state that let it "just work" changes.
 *
 * <p>This is a memory-visibility defect: no deterministic RED-state reproduction is possible
 * (a flaky sleep-and-poll test would be exactly the kind of test this repository's own
 * lock-proving tests -- {@code disablingBatchingHoldsTheLockAcrossFlushBlockingConcurrentSendLog},
 * {@code claimLogFlushWindowIsRaceSafeUnderConcurrentContention} -- were written to avoid). A
 * structural check on the declared field modifiers, in the style of {@code
 * CredentialStaticSurfaceInvariantTest}/{@code SoftDependencySignatureInvariantTest}, is the
 * deterministic substitute: it fails whenever the fix regresses, with no timing dependency.
 */
@DisplayName("UltiPanelLogTransmitter batch-config field volatility invariant")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class UltiPanelLogTransmitterBatchFieldVolatilityInvariantTest {

    /**
     * The three fields the panel's live {@code config} action mutates on the WebSocket thread,
     * read without synchronization by the logging thread ({@code addToBatch}) and the batch
     * scheduler thread ({@code sendBatch}).
     */
    private static final String[] CROSS_THREAD_MUTABLE_FIELDS = {
        "batchEnabled", "batchSize", "intervalMs"
    };

    @Test
    @DisplayName("batchEnabled, batchSize and intervalMs are all declared volatile")
    void crossThreadMutableBatchFieldsAreVolatile() throws NoSuchFieldException {
        for (String fieldName : CROSS_THREAD_MUTABLE_FIELDS) {
            Field field = UltiPanelLogTransmitter.class.getDeclaredField(fieldName);
            assertThat(Modifier.isVolatile(field.getModifiers()))
                .as("UltiPanelLogTransmitter#%s must be volatile -- it is written on the panel's "
                    + "WebSocket receive thread and read without synchronization on the logging "
                    + "and batch-scheduler threads", fieldName)
                .isTrue();
        }
    }
}
