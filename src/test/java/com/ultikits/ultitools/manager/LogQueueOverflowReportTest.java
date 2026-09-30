package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * #486: records the log-stream queue discards on overflow are counted, and the count is reported by
 * one WARNING at most once per interval, so a gap in the panel's log view is attributable rather
 * than silent. The count goes to the server log, not into the panel protocol.
 */
@DisplayName("Log-stream queue overflow is counted and reported (#486)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class LogQueueOverflowReportTest {

    private final Logger logger = Logger.getLogger("LogQueueOverflowReportTest");
    private final List<LogRecord> warnings = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel() == Level.WARNING) {
                warnings.add(record);
            }
        }

        @Override
        public void flush() {
            // Nothing buffered.
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    };
    private UltiPanelLogTransmitter transmitter;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        logger.setUseParentHandlers(false);
        logger.addHandler(capture);
        TestHelper.mockUltiToolsInstance(ultiTools -> lenient().when(ultiTools.getLogger()).thenReturn(logger));
        UltiPanelWebSocketClient client = mock(UltiPanelWebSocketClient.class);
        lenient().when(client.isConnected()).thenReturn(true);
        transmitter = new UltiPanelLogTransmitter(client, "test-server");
        // Under external drain the transmitter's own sender never sends, so the queue fills.
        transmitter.setExternalDrainMode(true);
    }

    @AfterEach
    void tearDown() {
        transmitter.shutdown();
        logger.removeHandler(capture);
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("records discarded on overflow are counted and reported once per interval")
    void overflowDiscardsAreCountedAndReportedOncePerInterval() {
        for (int i = 0; i < 1005; i++) {
            transmitter.info("line " + i, "test");
        }
        assertThat(transmitter.getQueueSize()).isEqualTo(1000);

        long now = System.currentTimeMillis();
        transmitter.reportDiscardedRecords(now);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage()).contains("discarded 5 record");

        transmitter.info("one more", "test");
        transmitter.reportDiscardedRecords(now + 1000);
        assertThat(warnings).as("at most one report per interval").hasSize(1);

        transmitter.reportDiscardedRecords(now + UltiPanelLogTransmitter.DISCARD_REPORT_INTERVAL_MS);
        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(1).getMessage()).contains("discarded 1 record");
    }

    @Test
    @DisplayName("nothing discarded, nothing reported")
    void noDiscardNoReport() {
        transmitter.info("only line", "test");

        transmitter.reportDiscardedRecords(System.currentTimeMillis());

        assertThat(warnings).isEmpty();
    }
}
