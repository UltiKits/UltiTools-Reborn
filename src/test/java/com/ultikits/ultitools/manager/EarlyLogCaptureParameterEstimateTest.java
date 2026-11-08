package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #571 item 6b: the start-up capture's byte bound counted a record's message, logger name and
 * exception but not its {@link LogRecord#getParameters() parameters}, which it holds as well. Each
 * parameter is now charged a bounded estimate, without calling {@code toString()} on it (that would
 * run arbitrary code inside the logging call).
 */
@DisplayName("The start-up capture charges a record's parameters (#571)")
class EarlyLogCaptureParameterEstimateTest {

    private final List<LogRecord> replayed = new ArrayList<>();
    private final Handler live = new Handler() {
        @Override
        public void publish(LogRecord record) {
            replayed.add(record);
        }

        @Override
        public void flush() {
            // Nothing is buffered: publish() records synchronously.
        }

        @Override
        public void close() {
            // Nothing to release: the handler holds only an in-memory list.
        }
    };

    @AfterEach
    void tearDown() {
        EarlyLogCapture.release();
    }

    private static void log(String message, Object... parameters) {
        LogRecord record = new LogRecord(Level.INFO, message);
        record.setLoggerName("Minecraft");
        record.setParameters(parameters.length == 0 ? null : parameters);
        // Handed straight to the capture: the root logger's other handlers (the console) format
        // the record and would call toString() themselves.
        for (Handler handler : Logger.getLogger("").getHandlers()) {
            if (handler instanceof EarlyLogCapture) {
                handler.publish(record);
            }
        }
    }

    /** An object whose {@code toString()} must never be called by the capture. */
    private static final class Untouchable {
        @Override
        public String toString() {
            throw new AssertionError("the capture called toString() on a parameter");
        }
    }

    @Test
    @DisplayName("a record whose parameters exceed the budget is dropped; the same record without them is kept")
    void parametersCountTowardsTheByteBound() {
        // "m {0} {1} {2}" from "Minecraft": 64 + 2*11 + 2*9 = 104 bytes without parameters.
        EarlyLogCapture.start(Collections.emptyList(), 100, 150, 60_000L, System::currentTimeMillis);
        log("m {0} {1} {2}");
        EarlyLogCapture.drainInto(live, () -> { });
        assertThat(replayed).hasSize(1);

        replayed.clear();
        EarlyLogCapture.start(Collections.emptyList(), 100, 150, 60_000L, System::currentTimeMillis);
        log("m {0} {1} {2}", new Untouchable(), new Untouchable(), new Untouchable());
        int dropped = EarlyLogCapture.drainInto(live, () -> { });

        assertThat(replayed).as("the parameters push the record past the 150-byte budget").isEmpty();
        assertThat(dropped).isEqualTo(1);
    }

    @Test
    @DisplayName("a long string parameter is charged by its length")
    void longStringParameterIsCharged() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            big.append('x');
        }
        EarlyLogCapture.start(Collections.emptyList(), 100, 1024, 60_000L, System::currentTimeMillis);
        log("m {0}", big.toString());
        int dropped = EarlyLogCapture.drainInto(live, () -> { });

        assertThat(replayed).isEmpty();
        assertThat(dropped).isEqualTo(1);
    }
}
