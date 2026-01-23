package com.ultikits.ultitools.handler;

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
        handler.flush();
        handler.close();
        // These methods are empty or just call super, but calling them ensures coverage
        // Verify no exceptions were thrown - test passes if we reach this point
        assertTrue(true, "flush() and close() completed without exceptions");
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
}
