package com.ultikits.ultitools.interfaces.impl.logger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PluginLoggerTest {

    private Logger mockLog;
    private PluginLogger logger;

    @BeforeEach
    void setUp() {
        mockLog = mock(Logger.class);
        logger = new PluginLogger("MyPlugin", mockLog);
    }

    // ==================== Basic Message Tests ====================
    
    @Nested
    @DisplayName("Basic Message Tests")
    class BasicMessageTests {
        
        @Test
        @DisplayName("info() should log with plugin prefix")
        void testInfo() {
            logger.info("Info message");
            verify(mockLog).info("[MyPlugin] Info message");
        }
        
        @Test
        @DisplayName("warn() should log with plugin prefix")
        void testWarn() {
            logger.warn("Warn message");
            verify(mockLog).warning("[MyPlugin] Warn message");
        }
        
        @Test
        @DisplayName("error() should log with plugin prefix")
        void testError() {
            logger.error("Error message");
            verify(mockLog).severe("[MyPlugin] Error message");
        }
        
        @Test
        @DisplayName("debug() should log with plugin prefix")
        void testDebug() {
            logger.debug("Debug message");
            verify(mockLog).fine("[MyPlugin] Debug message");
        }
        
        @Test
        @DisplayName("trace() should log with plugin prefix")
        void testTrace() {
            logger.trace("Trace message");
            verify(mockLog).finest("[MyPlugin] Trace message");
        }
    }

    // ==================== Parameterized Message Tests ====================
    
    @Nested
    @DisplayName("Parameterized Message Tests")
    class ParameterizedMessageTests {
        
        @Test
        @DisplayName("info() with params should log with plugin prefix")
        void testInfoWithParams() {
            logger.info("Message {0}", "param1");
            verify(mockLog).log(eq(Level.INFO), eq("[MyPlugin] Message {0}"), any(Object[].class));
        }
        
        @Test
        @DisplayName("warn() with params should log with plugin prefix")
        void testWarnWithParams() {
            logger.warn("Message {0}", "param1");
            verify(mockLog).log(eq(Level.WARNING), eq("[MyPlugin] Message {0}"), any(Object[].class));
        }
        
        @Test
        @DisplayName("error() with params should log with plugin prefix")
        void testErrorWithParams() {
            logger.error("Message {0}", "param1");
            verify(mockLog).log(eq(Level.SEVERE), eq("[MyPlugin] Message {0}"), any(Object[].class));
        }
    }

    // ==================== Throwable Tests ====================
    
    @Nested
    @DisplayName("Throwable Tests")
    class ThrowableTests {
        
        @Test
        @DisplayName("info() with throwable should log exception")
        void testInfoWithThrowable() {
            Exception ex = new RuntimeException("Test error");
            logger.info(ex);
            verify(mockLog).log(eq(Level.INFO), eq("[MyPlugin] "), eq(ex));
        }
        
        @Test
        @DisplayName("error() with throwable and message should log both")
        void testErrorWithThrowableAndMessage() {
            Exception ex = new RuntimeException("Test error");
            logger.error(ex, "Error occurred");
            verify(mockLog).log(eq(Level.SEVERE), eq("[MyPlugin] Error occurred"), eq(ex));
        }
    }
}
