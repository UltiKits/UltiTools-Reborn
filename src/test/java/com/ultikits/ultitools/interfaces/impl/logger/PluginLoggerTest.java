package com.ultikits.ultitools.interfaces.impl.logger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.hutool.log.Log;

class PluginLoggerTest {

    private Log mockLog;
    private PluginLogger logger;

    @BeforeEach
    void setUp() {
        mockLog = mock(Log.class);
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
            verify(mockLog).warn("[MyPlugin] Warn message");
        }
        
        @Test
        @DisplayName("error() should log with plugin prefix")
        void testError() {
            logger.error("Error message");
            verify(mockLog).error("[MyPlugin] Error message");
        }
        
        @Test
        @DisplayName("debug() should log with plugin prefix")
        void testDebug() {
            logger.debug("Debug message");
            verify(mockLog).debug("[MyPlugin] Debug message");
        }
        
        @Test
        @DisplayName("trace() should log with plugin prefix")
        void testTrace() {
            logger.trace("Trace message");
            verify(mockLog).trace("[MyPlugin] Trace message");
        }
    }

    // ==================== Message with Parameters Tests ====================
    
    @Nested
    @DisplayName("Message with Parameters Tests")
    class MessageWithParamsTests {
        
        @Test
        @DisplayName("info() with params should log with plugin prefix")
        void testInfoWithParams() {
            logger.info("Info {} {}", "param1", "param2");
            verify(mockLog).info("[MyPlugin] Info {} {}", "param1", "param2");
        }
        
        @Test
        @DisplayName("warn() with params should log with plugin prefix")
        void testWarnWithParams() {
            logger.warn("Warn {} {}", "a", "b");
            verify(mockLog).warn("[MyPlugin] Warn {} {}", "a", "b");
        }
        
        @Test
        @DisplayName("error() with params should log with plugin prefix")
        void testErrorWithParams() {
            logger.error("Error {}", 123);
            verify(mockLog).error("[MyPlugin] Error {}", 123);
        }
        
        @Test
        @DisplayName("debug() with params should log with plugin prefix")
        void testDebugWithParams() {
            logger.debug("Debug {} {} {}", 1, 2, 3);
            verify(mockLog).debug("[MyPlugin] Debug {} {} {}", 1, 2, 3);
        }
        
        @Test
        @DisplayName("trace() with params should log with plugin prefix")
        void testTraceWithParams() {
            logger.trace("Trace {}", "value");
            verify(mockLog).trace("[MyPlugin] Trace {}", "value");
        }
    }

    // ==================== Throwable Only Tests ====================
    
    @Nested
    @DisplayName("Throwable Only Tests")
    class ThrowableOnlyTests {
        
        private Throwable testException;
        
        @BeforeEach
        void setUp() {
            testException = new RuntimeException("Test exception");
        }
        
        @Test
        @DisplayName("info(Throwable) should log with plugin prefix")
        void testInfoThrowable() {
            logger.info(testException);
            verify(mockLog).info("[MyPlugin] ", testException);
        }
        
        @Test
        @DisplayName("warn(Throwable) should log with plugin prefix")
        void testWarnThrowable() {
            logger.warn(testException);
            verify(mockLog).warn("[MyPlugin] ", testException);
        }
        
        @Test
        @DisplayName("error(Throwable) should log with plugin prefix")
        void testErrorThrowable() {
            logger.error(testException);
            verify(mockLog).error("[MyPlugin] ", testException);
        }
        
        @Test
        @DisplayName("debug(Throwable) should log with plugin prefix")
        void testDebugThrowable() {
            logger.debug(testException);
            verify(mockLog).debug("[MyPlugin] ", testException);
        }
        
        @Test
        @DisplayName("trace(Throwable) should log with plugin prefix")
        void testTraceThrowable() {
            logger.trace(testException);
            verify(mockLog).trace("[MyPlugin] ", testException);
        }
    }

    // ==================== Throwable with Message Tests ====================
    
    @Nested
    @DisplayName("Throwable with Message Tests")
    class ThrowableWithMessageTests {
        
        private Throwable testException;
        
        @BeforeEach
        void setUp() {
            testException = new RuntimeException("Test exception");
        }
        
        @Test
        @DisplayName("info(Throwable, message) should log with plugin prefix")
        void testInfoThrowableMessage() {
            logger.info(testException, "Info with exception");
            verify(mockLog).info("[MyPlugin] Info with exception", testException);
        }
        
        @Test
        @DisplayName("warn(Throwable, message) should log with plugin prefix")
        void testWarnThrowableMessage() {
            logger.warn(testException, "Warn with exception");
            verify(mockLog).warn("[MyPlugin] Warn with exception", testException);
        }
        
        @Test
        @DisplayName("error(Throwable, message) should log with plugin prefix")
        void testErrorThrowableMessage() {
            logger.error(testException, "Error with exception");
            verify(mockLog).error("[MyPlugin] Error with exception", testException);
        }
        
        @Test
        @DisplayName("debug(Throwable, message) should log with plugin prefix")
        void testDebugThrowableMessage() {
            logger.debug(testException, "Debug with exception");
            verify(mockLog).debug("[MyPlugin] Debug with exception", testException);
        }
        
        @Test
        @DisplayName("trace(Throwable, message) should log with plugin prefix")
        void testTraceThrowableMessage() {
            logger.trace(testException, "Trace with exception");
            verify(mockLog).trace("[MyPlugin] Trace with exception", testException);
        }
    }

    // ==================== Throwable with Message and Params Tests ====================
    
    @Nested
    @DisplayName("Throwable with Message and Params Tests")
    class ThrowableWithMessageAndParamsTests {
        
        private Throwable testException;
        
        @BeforeEach
        void setUp() {
            testException = new RuntimeException("Test exception");
        }
        
        @Test
        @DisplayName("info(Throwable, message, params) should log with plugin prefix")
        void testInfoThrowableMessageParams() {
            Object[] params = new Object[]{"p1", "p2"};
            logger.info(testException, "Info {} {}", params);
            verify(mockLog).info("[MyPlugin] Info {} {}", params, testException);
        }
        
        @Test
        @DisplayName("warn(Throwable, message, params) should log with plugin prefix")
        void testWarnThrowableMessageParams() {
            Object[] params = new Object[]{"param"};
            logger.warn(testException, "Warn {}", params);
            verify(mockLog).warn("[MyPlugin] Warn {}", params, testException);
        }
        
        @Test
        @DisplayName("error(Throwable, message, params) should log with plugin prefix")
        void testErrorThrowableMessageParams() {
            Object[] params = new Object[]{404};
            logger.error(testException, "Error {}", params);
            verify(mockLog).error("[MyPlugin] Error {}", params, testException);
        }
        
        @Test
        @DisplayName("debug(Throwable, message, params) should log with plugin prefix")
        void testDebugThrowableMessageParams() {
            Object[] params = new Object[]{"a", "b", "c"};
            logger.debug(testException, "Debug {} {} {}", params);
            verify(mockLog).debug("[MyPlugin] Debug {} {} {}", params, testException);
        }
        
        @Test
        @DisplayName("trace(Throwable, message, params) should log with plugin prefix")
        void testTraceThrowableMessageParams() {
            Object[] params = new Object[]{true};
            logger.trace(testException, "Trace {}", params);
            verify(mockLog).trace("[MyPlugin] Trace {}", params, testException);
        }
    }

    // ==================== Edge Cases ====================
    
    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {
        
        @Test
        @DisplayName("Should handle empty message")
        void testEmptyMessage() {
            logger.info("");
            verify(mockLog).info("[MyPlugin] ");
        }
        
        @Test
        @DisplayName("Should handle message with special characters")
        void testSpecialCharacters() {
            logger.info("Message with 中文 and émojis 🎉");
            verify(mockLog).info("[MyPlugin] Message with 中文 and émojis 🎉");
        }
        
        @Test
        @DisplayName("Should handle null throwable")
        void testNullThrowable() {
            logger.info((Throwable) null);
            verify(mockLog).info("[MyPlugin] ", (Throwable) null);
        }
        
        @Test
        @DisplayName("Should handle empty params array")
        void testEmptyParamsArray() {
            logger.info("No params");
            verify(mockLog).info("[MyPlugin] No params");
        }
        
        @Test
        @DisplayName("Should work with different plugin names")
        void testDifferentPluginNames() {
            Log otherMockLog = mock(Log.class);
            PluginLogger otherLogger = new PluginLogger("OtherPlugin", otherMockLog);
            
            otherLogger.info("Test message");
            verify(otherMockLog).info("[OtherPlugin] Test message");
        }
        
        @Test
        @DisplayName("Should handle plugin name with special characters")
        void testPluginNameWithSpecialChars() {
            Log specialMockLog = mock(Log.class);
            PluginLogger specialLogger = new PluginLogger("Plugin-1.0", specialMockLog);
            
            specialLogger.info("Test");
            verify(specialMockLog).info("[Plugin-1.0] Test");
        }
    }
}
