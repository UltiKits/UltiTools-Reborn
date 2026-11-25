package com.ultikits.ultitools.handler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.text.LocaleResolver;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.internal.FrameworkMessages;
import com.ultikits.ultitools.text.internal.MessageRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.manager.UltiPanelLogTransmitter;

class SystemLogHandlerTest {

    private UltiPanelLogTransmitter mockTransmitter;
    private SystemLogHandler handler;

    @BeforeEach
    void setUp() {
        mockTransmitter = mock(UltiPanelLogTransmitter.class);
        handler = new SystemLogHandler(mockTransmitter);
    }

    @Test
    void testPublishInfo() {
        LogRecord record = new LogRecord(Level.INFO, "Test message");
        // Use a logger name that triggers plugin detection
        record.setLoggerName("plugin.MyPlugin");
        
        handler.publish(record);
        
        verify(mockTransmitter).sendLog(eq("info"), eq("Test message"), eq("plugin:MyPlugin"), isNull());
    }

    @Test
    void testPublishWarning() {
        LogRecord record = new LogRecord(Level.WARNING, "Warning message");
        record.setLoggerName("plugin.MyPlugin");
        
        handler.publish(record);
        
        verify(mockTransmitter).sendLog(eq("warning"), eq("Warning message"), eq("plugin:MyPlugin"), isNull());
    }

    @Test
    void testPublishError() {
        LogRecord record = new LogRecord(Level.SEVERE, "Error message");
        record.setLoggerName("plugin.MyPlugin");
        Throwable thrown = new RuntimeException("Oops");
        record.setThrown(thrown);
        
        handler.publish(record);
        
        verify(mockTransmitter).sendLog(eq("error"), eq("Error message"), eq("plugin:MyPlugin"), eq(thrown));
    }

    @Test
    void testExcludedLogger() {
        handler.addExcludedLogger("com.ignored.Logger");
        LogRecord record = new LogRecord(Level.INFO, "Ignored message");
        record.setLoggerName("com.ignored.Logger");
        
        handler.publish(record);
        
        verifyNoInteractions(mockTransmitter);
    }

    @Test
    void testLevelFiltering() {
        handler.removeEnabledLevel("info");
        LogRecord record = new LogRecord(Level.INFO, "Info message");
        record.setLoggerName("plugin.MyPlugin");
        
        handler.publish(record);
        
        verifyNoInteractions(mockTransmitter);
    }

    @Test
    void testLogSourceDetection() {
        // Test server source
        LogRecord serverRecord = new LogRecord(Level.INFO, "Server msg");
        serverRecord.setLoggerName("net.minecraft.server.MinecraftServer");
        handler.publish(serverRecord);
        verify(mockTransmitter).sendLog(eq("info"), eq("Server msg"), eq("server"), isNull());

        // Reset mock to clear previous interactions
        reset(mockTransmitter);

        // Test database source
        // Note: com.zaxxer.hikari is excluded by default, so we use org.hibernate
        LogRecord dbRecord = new LogRecord(Level.INFO, "DB msg");
        dbRecord.setLoggerName("org.hibernate.Session");
        handler.publish(dbRecord);
        verify(mockTransmitter).sendLog(eq("info"), eq("DB msg"), eq("database"), isNull());
    }
    
    @Test
    void testFormatMessageWithParams() {
        // The implementation uses String.format, so we use %s
        LogRecord record = new LogRecord(Level.INFO, "Hello %s");
        record.setParameters(new Object[]{"World"});
        record.setLoggerName("test");
        
        handler.publish(record);
        
        verify(mockTransmitter).sendLog(eq("info"), eq("Hello World"), anyString(), isNull());
    }
    
    @Test
    void testFlushAndClose() {
        // These methods are empty or just call super, but calling them ensures coverage
        // Verify no exceptions were thrown - assertDoesNotThrow verifies this
        assertDoesNotThrow(() -> {
            handler.flush();
            handler.close();
        }, "flush() and close() should complete without exceptions");
    }
    
    @Test
    void testConfigurationMethods() {
        handler.addEnabledLevel("debug");
        assertTrue(handler.getEnabledLevels().contains("debug"));
        
        handler.removeEnabledLevel("debug");
        assertFalse(handler.getEnabledLevels().contains("debug"));
        
        handler.addExcludedLogger("test.logger");
        assertTrue(handler.getExcludedLoggers().contains("test.logger"));
        
        handler.removeExcludedLogger("test.logger");
        assertFalse(handler.getExcludedLoggers().contains("test.logger"));
    }

    @Test
    void testLoopPrevention() {
        LogRecord record = new LogRecord(Level.INFO, "Loop");
        record.setLoggerName("com.ultikits.ultitools.manager.UltiPanelLogTransmitter");
        handler.publish(record);
        verifyNoInteractions(mockTransmitter);
    }

    @Test
    void testUltiToolsSource() {
        LogRecord record = new LogRecord(Level.INFO, "UltiTools msg");
        record.setLoggerName("com.ultikits.ultitools.Core");
        handler.publish(record);
        verify(mockTransmitter).sendLog(eq("info"), eq("UltiTools msg"), eq("plugin:UltiTools"), isNull());
    }

    @Test
    void testNetworkSource() {
        LogRecord record = new LogRecord(Level.INFO, "Net msg");
        record.setLoggerName("io.netty.channel.Channel");
        handler.publish(record);
        verify(mockTransmitter).sendLog(eq("info"), eq("Net msg"), eq("network"), isNull());
    }
    
    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void testFormatMessageExceptionFallback(String code) throws Exception {
        try (var support = new CommandMessageTestSupport(code);
                var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code))) {
            assertFallbackMessage(code);
        }
    }

    private void assertFallbackMessage(String code) {
        // Force an exception in String.format by using invalid format specifier
        // But String.format throws IllegalFormatException which is unchecked.
        // The code catches Exception.
        LogRecord record = new LogRecord(Level.INFO, "Hello %d"); // Expects integer
        record.setParameters(new Object[]{"World"}); // String provided
        record.setLoggerName("test");

        handler.publish(record);

        // Expect fallback: "Hello %d [参数: World]"
        verify(mockTransmitter).sendLog(eq("info"), eq("zh".equals(code)
                ? "Hello %d [参数: World]" : "Hello %d [parameters: World]"), anyString(), isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void configurationAndFailureDiagnosticsUseServerLanguage(String code) throws Exception {
        List<LogRecord> records = new ArrayList<>();
        Logger logger = collectingLogger(records);
        UltiTools plugin = mock(UltiTools.class);
        YamlConfiguration config = mock(YamlConfiguration.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getConfig()).thenReturn(config);
        try (var support = new CommandMessageTestSupport(code);
                var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code));
                MockedStatic<UltiTools> singleton = mockStatic(UltiTools.class)) {
            singleton.when(UltiTools::getInstance).thenReturn(plugin);
            handler.loadConfiguration();
            when(config.contains(anyString())).thenThrow(new IllegalStateException("broken"));
            handler.loadConfiguration();
            doThrow(new IllegalStateException("sink down")).when(mockTransmitter)
                    .sendLog(anyString(), anyString(), anyString(), isNull());
            handler.publish(new LogRecord(Level.INFO, "outer"));
            assertThat(records).extracting(LogRecord::getMessage).containsExactly("zh".equals(code)
                    ? new String[]{"[UltiPanel] 系统日志处理器配置已加载",
                        "[UltiPanel] 加载日志配置失败，使用默认配置: broken",
                        "[UltiPanel] SystemLogHandler处理日志记录失败: sink down"}
                    : new String[]{"[UltiPanel] System log handler configuration loaded",
                        "[UltiPanel] Failed to load the log configuration, using the defaults: broken",
                        "[UltiPanel] SystemLogHandler failed to process a log record: sink down"});
            assertThat(records).extracting(LogRecord::getLevel)
                    .containsExactly(Level.INFO, Level.WARNING, Level.WARNING);
            assertThat(records).extracting(LogRecord::getThrown).containsOnlyNulls();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void configurationSummaryPreservesAllFiveLines(String code) throws Exception {
        try (var support = new CommandMessageTestSupport(code);
                var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code))) {
            for (boolean connected : new boolean[]{true, false}) {
                SystemLogHandler configured = new SystemLogHandler(connected ? mockTransmitter : null);
                configured.setEnabledLevels(Set.of("info"));
                configured.setExcludedLoggers(Set.of("excluded"));
                String expected = "zh".equals(code)
                        ? "SystemLogHandler配置信息:\n- 最小日志级别: INFO\n- 启用的级别: [info]\n"
                            + "- 排除的记录器数量: 1\n- 日志传输器状态: " + (connected ? "已连接" : "未连接")
                        : "SystemLogHandler configuration:\n- Minimum log level: INFO\n- Enabled levels: [info]\n"
                            + "- Excluded loggers: 1\n- Log transmitter status: " + (connected ? "Connected" : "Not connected");
                assertThat(configured.getConfigurationInfo()).isEqualTo(expected);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void missingKeyWarningInsideHandlerIsBounded(String code) throws Exception {
        List<LogRecord> records = new ArrayList<>();
        Logger logger = collectingLogger(records);
        logger.addHandler(handler);
        Messages messages = mock(Messages.class);
        var renderer = new MessageRenderer(namespace -> null, mock(LocaleResolver.class), logger);
        when(messages.render(any(), any(Locale.class)))
                .thenAnswer(call -> renderer.render(call.getArgument(0), call.getArgument(1)));
        LogRecord invalid = new LogRecord(Level.INFO, "value %d");
        invalid.setParameters(new Object[]{"not a number"});
        try (var scope = FrameworkMessages.overrideForTests(messages, Locale.forLanguageTag(code))) {
            assertDoesNotThrow(() -> {
                handler.publish(invalid);
                handler.publish(invalid);
            });
            assertThat(records).hasSize(1);
            assertThat(records.getFirst().getMessage()).isEqualTo("Missing message {0} for {1}");
            assertThat(records.getFirst().getLevel()).isEqualTo(Level.WARNING);
            verify(mockTransmitter, times(2)).sendLog(eq("info"), anyString(), anyString(), isNull());
            verifyNoMoreInteractions(mockTransmitter);
        } finally {
            logger.removeHandler(handler);
        }
    }

    private static Logger collectingLogger(List<LogRecord> records) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { /* No buffer. */ }
            @Override public void close() { /* No resource. */ }
        });
        return logger;
    }

    // ==================== CR-02: ErrorReportCollector decoupled from the levels filter ====================

    @Test
    void testErrorReportingReachesCollectorEvenWhenErrorLevelExcludedFromDelivery() {
        // #433 made the levels filter genuinely effective; CR-02: excluding "error" from the
        // panel's live log view must NOT also silently disable ErrorReportCollector's automatic
        // SEVERE-exception reporting -- the two are independent declared surfaces.
        handler.removeEnabledLevel("error");

        Throwable thrown = new RuntimeException("boom");
        LogRecord record = new LogRecord(Level.SEVERE, "Severe with throwable");
        record.setLoggerName("plugin.MyPlugin");
        record.setThrown(thrown);

        com.ultikits.ultitools.manager.ErrorReportCollector mockErc =
                mock(com.ultikits.ultitools.manager.ErrorReportCollector.class);
        com.ultikits.ultitools.UltiTools mockInstance = mock(com.ultikits.ultitools.UltiTools.class);
        org.mockito.Mockito.when(mockInstance.getErrorReportCollector()).thenReturn(mockErc);

        try (org.mockito.MockedStatic<com.ultikits.ultitools.UltiTools> staticMock =
                org.mockito.Mockito.mockStatic(com.ultikits.ultitools.UltiTools.class)) {
            staticMock.when(com.ultikits.ultitools.UltiTools::getInstance).thenReturn(mockInstance);

            handler.publish(record);
        }

        // Panel delivery IS suppressed (levels filter excludes "error")...
        verifyNoInteractions(mockTransmitter);
        // ...but the ErrorReportCollector report is NOT suppressed.
        verify(mockErc).reportError(eq(thrown), eq("MyPlugin"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void testErrorReportingStillWorksWhenErrorLevelIsEnabled() {
        // Control: the "error" level stays enabled by default, so both the panel delivery AND
        // the ErrorReportCollector report fire -- proves the decoupling didn't accidentally
        // break the ordinary case.
        Throwable thrown = new RuntimeException("boom");
        LogRecord record = new LogRecord(Level.SEVERE, "Severe with throwable, error enabled");
        record.setLoggerName("plugin.MyPlugin");
        record.setThrown(thrown);

        com.ultikits.ultitools.manager.ErrorReportCollector mockErc =
                mock(com.ultikits.ultitools.manager.ErrorReportCollector.class);
        com.ultikits.ultitools.UltiTools mockInstance = mock(com.ultikits.ultitools.UltiTools.class);
        org.mockito.Mockito.when(mockInstance.getErrorReportCollector()).thenReturn(mockErc);

        try (org.mockito.MockedStatic<com.ultikits.ultitools.UltiTools> staticMock =
                org.mockito.Mockito.mockStatic(com.ultikits.ultitools.UltiTools.class)) {
            staticMock.when(com.ultikits.ultitools.UltiTools::getInstance).thenReturn(mockInstance);

            handler.publish(record);
        }

        verify(mockTransmitter).sendLog(eq("error"), eq("Severe with throwable, error enabled"),
                eq("plugin:MyPlugin"), eq(thrown));
        verify(mockErc).reportError(eq(thrown), eq("MyPlugin"), org.mockito.ArgumentMatchers.any());
    }

    // ==================== Gate-2 P2: enabling "debug" must lower the handler's own JUL floor ====================

    @Test
    void testDebugNotInEnabledLevelsFineRecordNeverReachesTransmitter() {
        // Control: "debug" is NOT in the default enabledLevels ({info, warning, error}) -- a FINE
        // record should not be delivered, same as before this fix.
        LogRecord record = new LogRecord(Level.FINE, "Fine, debug not enabled");
        record.setLoggerName("plugin.MyPlugin");

        handler.publish(record);

        verifyNoInteractions(mockTransmitter);
    }

    @Test
    void testEnablingDebugViaSetEnabledLevelsLetsFineRecordsThrough() {
        // Gate-2 P2: before this fix, java.util.logging.Handler#isLoggable(record) (called from
        // shouldProcessRecord BEFORE this class's own enabledLevels check) rejected FINE records
        // outright, because the handler's own level floor stayed at Level.INFO regardless of
        // what enabledLevels said -- so a panel request enabling "debug" had no observable effect.
        java.util.Set<String> withDebug = new java.util.HashSet<>(handler.getEnabledLevels());
        withDebug.add("debug");
        handler.setEnabledLevels(withDebug);

        LogRecord record = new LogRecord(Level.FINE, "Fine, debug now enabled");
        record.setLoggerName("plugin.MyPlugin");

        handler.publish(record);

        verify(mockTransmitter).sendLog(eq("debug"), eq("Fine, debug now enabled"), eq("plugin:MyPlugin"), isNull());
    }

    @Test
    void testAddEnabledLevelDebugAlsoLowersTheHandlerFloor() {
        LogRecord record = new LogRecord(Level.FINEST, "Finest, via addEnabledLevel");
        record.setLoggerName("plugin.MyPlugin");

        handler.addEnabledLevel("debug");
        handler.publish(record);

        verify(mockTransmitter).sendLog(eq("debug"), eq("Finest, via addEnabledLevel"), eq("plugin:MyPlugin"), isNull());
    }

    @Test
    void testRemovingDebugRestoresTheHandlerFloorToInfo() {
        handler.addEnabledLevel("debug");
        handler.removeEnabledLevel("debug");

        LogRecord record = new LogRecord(Level.FINE, "Fine, debug removed again");
        record.setLoggerName("plugin.MyPlugin");

        handler.publish(record);

        // Restored to the INFO floor -- the record never reaches isLoggable's threshold at all,
        // let alone the (now again debug-less) enabledLevels check.
        verifyNoInteractions(mockTransmitter);
    }

    // ==================== Gate-2 P1 (round 9): PUBLISHING reentrancy guard ====================

    @Test
    void testReentrantPublishIsDroppedNotRecursed() {
        // Gate-2 finding, round 9: a diagnostic logged from WITHIN sendLog() (through the shared
        // plugin logger) can re-enter publish() on the SAME thread before the outer call returns
        // -- exactly the shape that produced three separate StackOverflowError instances in
        // earlier rounds. The PUBLISHING ThreadLocal guard must drop the re-entrant call outright
        // rather than let it recurse.
        LogRecord inner = new LogRecord(Level.INFO, "Inner, re-entrant");
        inner.setLoggerName("plugin.MyPlugin");

        LogRecord outer = new LogRecord(Level.INFO, "Outer");
        outer.setLoggerName("plugin.MyPlugin");

        org.mockito.Mockito.doAnswer(invocation -> {
            // Simulate a call back into this same handler, synchronously, on the same thread --
            // the shape a diagnostic logged from inside sendLog() would take.
            handler.publish(inner);
            return null;
        }).when(mockTransmitter).sendLog(eq("info"), eq("Outer"), anyString(), isNull());

        handler.publish(outer);

        // Only the OUTER call's own sendLog fired; the re-entrant INNER call was dropped before
        // it ever reached sendLog.
        verify(mockTransmitter).sendLog(eq("info"), eq("Outer"), anyString(), isNull());
        verify(mockTransmitter, org.mockito.Mockito.never())
                .sendLog(eq("info"), eq("Inner, re-entrant"), anyString(), isNull());
    }

    @Test
    void testPublishingGuardIsResetAfterEachTopLevelCallSoSubsequentCallsStillWork() {
        // The ThreadLocal must be cleared in a finally block, so a later, genuinely NEW top-level
        // call on the same thread is still processed normally -- the guard must not leak "true"
        // across unrelated calls.
        LogRecord first = new LogRecord(Level.INFO, "First");
        first.setLoggerName("plugin.MyPlugin");
        handler.publish(first);
        verify(mockTransmitter).sendLog(eq("info"), eq("First"), anyString(), isNull());

        LogRecord second = new LogRecord(Level.INFO, "Second");
        second.setLoggerName("plugin.MyPlugin");
        handler.publish(second);
        verify(mockTransmitter).sendLog(eq("info"), eq("Second"), anyString(), isNull());
    }
}
