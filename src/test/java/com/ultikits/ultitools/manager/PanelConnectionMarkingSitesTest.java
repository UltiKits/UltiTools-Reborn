package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.text.internal.FrameworkMessages;
import java.util.Locale;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.ultikits.ultitools.handler.SystemLogHandler;
import com.ultikits.ultitools.testutil.LoggedLines;
import com.ultikits.ultitools.websocket.PanelConnectionLog;

/**
 * #587 item 1a: two diagnostics that #586 routed through {@link PanelConnectionLog} had no test
 * asserting that they are marked as panel-connection lines, so a change back to a plain
 * {@code getLogger().log(...)} -- which the log stream would carry to the panel, and on Paper feed
 * back into the stream's own handler -- would pass the suite. The failure report of
 * {@link SystemLogHandler#publish} is pinned here. The second site, the catch of
 * {@code UltiPanelLogTransmitter#shutdown} around its v1 flush, was removed in 6.4.0 with the v1 batch sender:
 * {@code shutdown} now only switches transmission off and has no failure path left to mark.
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

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    @DisplayName("SystemLogHandler: a record it cannot hand on is reported as a marked WARNING")
    void systemLogHandlerFailureIsMarked(String code) throws Exception {
        try (var support = new CommandMessageTestSupport(code);
                var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code))) {
            UltiPanelLogTransmitter transmitter = mock(UltiPanelLogTransmitter.class);
            doThrow(new IllegalStateException("transmitter broke"))
                    .when(transmitter).sendLog(anyString(), anyString(), anyString(), any());
            SystemLogHandler handler = new SystemLogHandler(transmitter);
            LogRecord record = new LogRecord(Level.INFO, "a line");
            record.setLoggerName("plugin.MyPlugin");

            handler.publish(record);

            LogRecord report = onlyWarning();
            assertThat(report.getMessage()).isEqualTo("zh".equals(code)
                    ? "[UltiPanel] SystemLogHandler处理日志记录失败: transmitter broke"
                    : "[UltiPanel] SystemLogHandler failed to process a log record: transmitter broke");
            assertThat(PanelConnectionLog.isPanelConnectionRecord(report)).isTrue();
        }
    }
}
