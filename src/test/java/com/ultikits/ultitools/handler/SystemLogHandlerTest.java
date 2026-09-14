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
    
    @Test
    void testFormatMessageExceptionFallback() {
        // Force an exception in String.format by using invalid format specifier
        // But String.format throws IllegalFormatException which is unchecked.
        // The code catches Exception.
        LogRecord record = new LogRecord(Level.INFO, "Hello %d"); // Expects integer
        record.setParameters(new Object[]{"World"}); // String provided
        record.setLoggerName("test");

        handler.publish(record);

        // Expect fallback: "Hello %d [参数: World]"
        verify(mockTransmitter).sendLog(eq("info"), eq("Hello %d [参数: World]"), anyString(), isNull());
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
}
