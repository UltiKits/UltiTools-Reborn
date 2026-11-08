package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.handler.SystemLogHandler;
import com.ultikits.ultitools.testutil.LoggedLines;
import com.ultikits.ultitools.websocket.PanelConnectionLog;

/**
 * #587 item 1a: two diagnostics that #586 routed through {@link PanelConnectionLog} had no test
 * asserting that they are marked as panel-connection lines, so a change back to a plain
 * {@code getLogger().log(...)} -- which the log stream would carry to the panel, and on Paper feed
 * back into the stream's own handler -- would pass the suite. Both are pinned here: the failure
 * report of {@link SystemLogHandler#publish} and the catch of {@link UltiPanelLogTransmitter#shutdown}.
 */
@DisplayName("Stream-side diagnostics are panel-connection lines (#587)")
class PanelConnectionMarkingSitesTest {

    private MockedStatic<UltiTools> instanceMock;
    private Logger pluginLogger;

    @BeforeEach
    void setUp() {
        pluginLogger = mock(Logger.class);
        lenient().when(pluginLogger.getName()).thenReturn("UltiTools");
        UltiTools plugin = mock(UltiTools.class);
        lenient().when(plugin.getLogger()).thenReturn(pluginLogger);
        lenient().when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        instanceMock = mockStatic(UltiTools.class);
        instanceMock.when(UltiTools::getInstance).thenReturn(plugin);
    }

    @AfterEach
    void tearDown() {
        instanceMock.close();
    }

    private LogRecord onlyWarning() {
        List<String> plain = LoggedLines.at(pluginLogger, Level.WARNING);
        List<LogRecord> records = LoggedLines.records(pluginLogger);
        assertThat(plain).as("exactly one WARNING line").hasSize(1);
        assertThat(records).as("logged as a record, not as a plain line").hasSize(1);
        return records.get(0);
    }

    @Test
    @DisplayName("SystemLogHandler: a record it cannot hand on is reported as a marked WARNING")
    void systemLogHandlerFailureIsMarked() {
        UltiPanelLogTransmitter transmitter = mock(UltiPanelLogTransmitter.class);
        doThrow(new IllegalStateException("transmitter broke"))
                .when(transmitter).sendLog(anyString(), anyString(), anyString(), any());
        SystemLogHandler handler = new SystemLogHandler(transmitter);
        LogRecord record = new LogRecord(Level.INFO, "a line");
        record.setLoggerName("plugin.MyPlugin");

        handler.publish(record);

        LogRecord report = onlyWarning();
        assertThat(report.getMessage()).contains("transmitter broke");
        assertThat(PanelConnectionLog.isPanelConnectionRecord(report)).isTrue();
    }

    @Test
    @DisplayName("UltiPanelLogTransmitter#shutdown: a failure while closing is reported as a marked WARNING")
    void transmitterShutdownFailureIsMarked() {
        UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter((timestamp, level, message) -> { });
        UltiPanelLogTransmitter failing = spy(transmitter);
        doThrow(new IllegalStateException("flush broke")).when(failing).flushLogs();
        try {
            failing.shutdown();

            LogRecord report = onlyWarning();
            assertThat(report.getMessage()).contains("flush broke");
            assertThat(PanelConnectionLog.isPanelConnectionRecord(report)).isTrue();
        } finally {
            // The failed shutdown left the scheduler running; the real one stops it.
            transmitter.shutdown();
        }
    }
}
