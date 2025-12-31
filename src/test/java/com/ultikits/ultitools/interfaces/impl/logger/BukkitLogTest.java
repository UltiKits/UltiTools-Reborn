package com.ultikits.ultitools.interfaces.impl.logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.hutool.log.level.Level;

class BukkitLogTest {

    private static Logger mockLogger;
    private static boolean serverSetByUs = false;

    @BeforeAll
    static void setUpClass() {
        if (Bukkit.getServer() == null) {
            Server mockServer = mock(Server.class);
            mockLogger = mock(Logger.class);
            when(mockServer.getLogger()).thenReturn(mockLogger);
            Bukkit.setServer(mockServer);
            serverSetByUs = true;
        } else {
            mockLogger = Bukkit.getLogger();
            if (mockLogger == null) {
                // Try to set a logger on the existing server if it's a mock
                Server server = Bukkit.getServer();
                if (mockingDetails(server).isMock()) {
                    mockLogger = mock(Logger.class);
                    when(server.getLogger()).thenReturn(mockLogger);
                }
            }
        }
    }

    @AfterAll
    static void tearDownClass() {
        // Note: Cannot call Bukkit.setServer(null) as it throws UnsupportedOperationException
        // Reset level to default
        BukkitLog.setLevel(Level.INFO);
    }

    @BeforeEach
    void setUp() {
        if (mockLogger != null && mockingDetails(mockLogger).isMock()) {
            reset(mockLogger);
        }
        BukkitLog.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        // Ensure level is reset after each test
        BukkitLog.setLevel(Level.INFO);
    }

    // ==================== Constructor Tests ====================
    
    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {
        
        @Test
        @DisplayName("Should create log with class name")
        void testConstructorWithClass() {
            BukkitLog log = new BukkitLog(BukkitLogTest.class);
            assertThat(log.getName()).isEqualTo(BukkitLogTest.class.getName());
        }
        
        @Test
        @DisplayName("Should create log with string name")
        void testConstructorWithString() {
            BukkitLog log = new BukkitLog("CustomLogger");
            assertThat(log.getName()).isEqualTo("CustomLogger");
        }
        
        @Test
        @DisplayName("Should handle null class")
        void testConstructorWithNullClass() {
            BukkitLog log = new BukkitLog((Class<?>) null);
            assertThat(log.getName()).isEqualTo("null");
        }
        
        @Test
        @DisplayName("Should handle null string name")
        void testConstructorWithNullString() {
            BukkitLog log = new BukkitLog((String) null);
            assertThat(log.getName()).isNull();
        }
    }

    // ==================== Level Tests ====================
    
    @Nested
    @DisplayName("Level Tests")
    class LevelTests {
        
        @Test
        @DisplayName("setLevel should throw on null")
        void testSetLevelNull() {
            assertThatThrownBy(() -> BukkitLog.setLevel(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
        
        @Test
        @DisplayName("Should respect TRACE level")
        void testTraceLevelEnabled() {
            BukkitLog.setLevel(Level.TRACE);
            BukkitLog log = new BukkitLog("Test");
            
            assertThat(log.isTraceEnabled()).isTrue();
            assertThat(log.isDebugEnabled()).isTrue();
            assertThat(log.isInfoEnabled()).isTrue();
            assertThat(log.isWarnEnabled()).isTrue();
            assertThat(log.isErrorEnabled()).isTrue();
        }
        
        @Test
        @DisplayName("Should respect DEBUG level")
        void testDebugLevelEnabled() {
            BukkitLog.setLevel(Level.DEBUG);
            BukkitLog log = new BukkitLog("Test");
            
            assertThat(log.isTraceEnabled()).isFalse();
            assertThat(log.isDebugEnabled()).isTrue();
            assertThat(log.isInfoEnabled()).isTrue();
            assertThat(log.isWarnEnabled()).isTrue();
            assertThat(log.isErrorEnabled()).isTrue();
        }
        
        @Test
        @DisplayName("Should respect INFO level")
        void testInfoLevelEnabled() {
            BukkitLog.setLevel(Level.INFO);
            BukkitLog log = new BukkitLog("Test");
            
            assertThat(log.isTraceEnabled()).isFalse();
            assertThat(log.isDebugEnabled()).isFalse();
            assertThat(log.isInfoEnabled()).isTrue();
            assertThat(log.isWarnEnabled()).isTrue();
            assertThat(log.isErrorEnabled()).isTrue();
        }
        
        @Test
        @DisplayName("Should respect WARN level")
        void testWarnLevelEnabled() {
            BukkitLog.setLevel(Level.WARN);
            BukkitLog log = new BukkitLog("Test");
            
            assertThat(log.isTraceEnabled()).isFalse();
            assertThat(log.isDebugEnabled()).isFalse();
            assertThat(log.isInfoEnabled()).isFalse();
            assertThat(log.isWarnEnabled()).isTrue();
            assertThat(log.isErrorEnabled()).isTrue();
        }
        
        @Test
        @DisplayName("Should respect ERROR level")
        void testErrorLevelEnabled() {
            BukkitLog.setLevel(Level.ERROR);
            BukkitLog log = new BukkitLog("Test");
            
            assertThat(log.isTraceEnabled()).isFalse();
            assertThat(log.isDebugEnabled()).isFalse();
            assertThat(log.isInfoEnabled()).isFalse();
            assertThat(log.isWarnEnabled()).isFalse();
            assertThat(log.isErrorEnabled()).isTrue();
        }
        
        @Test
        @DisplayName("Should respect OFF level - nothing enabled")
        void testOffLevelDisablesAll() {
            BukkitLog.setLevel(Level.OFF);
            BukkitLog log = new BukkitLog("Test");
            
            assertThat(log.isEnabled(Level.FATAL)).isFalse();
            assertThat(log.isEnabled(Level.ERROR)).isFalse();
            assertThat(log.isEnabled(Level.WARN)).isFalse();
            assertThat(log.isEnabled(Level.INFO)).isFalse();
            assertThat(log.isEnabled(Level.DEBUG)).isFalse();
            assertThat(log.isEnabled(Level.TRACE)).isFalse();
        }
        
        @Test
        @DisplayName("Should respect ALL level - everything enabled")
        void testAllLevelEnablesAll() {
            BukkitLog.setLevel(Level.ALL);
            BukkitLog log = new BukkitLog("Test");
            
            assertThat(log.isEnabled(Level.FATAL)).isTrue();
            assertThat(log.isEnabled(Level.ERROR)).isTrue();
            assertThat(log.isEnabled(Level.WARN)).isTrue();
            assertThat(log.isEnabled(Level.INFO)).isTrue();
            assertThat(log.isEnabled(Level.DEBUG)).isTrue();
            assertThat(log.isEnabled(Level.TRACE)).isTrue();
            assertThat(log.isEnabled(Level.ALL)).isTrue();
        }
    }

    // ==================== Log Method Tests ====================
    
    @Nested
    @DisplayName("Log Method Tests")
    class LogMethodTests {
        
        @Test
        @DisplayName("trace() should call finer when enabled")
        void testTrace() {
            if (mockLogger == null) return;
            BukkitLog.setLevel(Level.TRACE);
            BukkitLog log = new BukkitLog("TestLog");
            
            log.trace("Trace message {}", "arg");
            verify(mockLogger).finer("TestLog: Trace message arg");
        }
        
        @Test
        @DisplayName("debug() should call fine when enabled")
        void testDebug() {
            if (mockLogger == null) return;
            BukkitLog.setLevel(Level.DEBUG);
            BukkitLog log = new BukkitLog("TestLog");
            
            log.debug("Debug message {}", "arg");
            verify(mockLogger).fine("TestLog: Debug message arg");
        }
        
        @Test
        @DisplayName("info() should call info when enabled")
        void testInfo() {
            if (mockLogger == null) return;
            BukkitLog log = new BukkitLog("TestLog");
            
            log.info("Hello {}", "World");
            verify(mockLogger).info("TestLog: Hello World");
        }
        
        @Test
        @DisplayName("warn() should call warning when enabled")
        void testWarn() {
            if (mockLogger == null) return;
            BukkitLog log = new BukkitLog("TestLog");
            
            log.warn("Warning message");
            verify(mockLogger).warning("TestLog: Warning message");
        }
        
        @Test
        @DisplayName("error() should call severe when enabled")
        void testError() {
            if (mockLogger == null) return;
            BukkitLog log = new BukkitLog("TestLog");
            
            log.error("Error message");
            verify(mockLogger).severe("TestLog: Error message");
        }
        
        @Test
        @DisplayName("FATAL level should call severe")
        void testFatal() {
            if (mockLogger == null) return;
            BukkitLog log = new BukkitLog("TestLog");
            
            log.log(null, Level.FATAL, null, "Fatal error");
            verify(mockLogger).severe("TestLog: Fatal error");
        }
        
        @Test
        @DisplayName("ALL level should call finest")
        void testAllLevel() {
            if (mockLogger == null) return;
            BukkitLog.setLevel(Level.ALL);
            BukkitLog log = new BukkitLog("TestLog");
            
            log.log(null, Level.ALL, null, "All level message");
            verify(mockLogger).finest("TestLog: All level message");
        }
        
        @Test
        @DisplayName("OFF level should not log anything")
        void testOffLevel() {
            if (mockLogger == null) return;
            BukkitLog log = new BukkitLog("TestLog");
            
            log.log(null, Level.OFF, null, "Should not log");
            verify(mockLogger, never()).info(anyString());
            verify(mockLogger, never()).warning(anyString());
            verify(mockLogger, never()).severe(anyString());
            verify(mockLogger, never()).fine(anyString());
            verify(mockLogger, never()).finer(anyString());
            verify(mockLogger, never()).finest(anyString());
        }
    }

    // ==================== Disabled Level Tests ====================
    
    @Nested
    @DisplayName("Disabled Level Tests")
    class DisabledLevelTests {
        
        @Test
        @DisplayName("Should not log when level is disabled")
        void testLevelDisabled() {
            if (mockLogger == null) return;
            BukkitLog.setLevel(Level.WARN);
            BukkitLog log = new BukkitLog("TestLog");
            
            log.info("Should not log");
            verify(mockLogger, never()).info(anyString());
            
            log.debug("Should not log");
            verify(mockLogger, never()).fine(anyString());
            
            log.trace("Should not log");
            verify(mockLogger, never()).finer(anyString());
        }
        
        @Test
        @DisplayName("Should log when level is enabled")
        void testLevelEnabled() {
            if (mockLogger == null) return;
            BukkitLog.setLevel(Level.WARN);
            BukkitLog log = new BukkitLog("TestLog");
            
            log.warn("Should log");
            verify(mockLogger).warning("TestLog: Should log");
            
            log.error("Should log error");
            verify(mockLogger).severe("TestLog: Should log error");
        }
    }

    // ==================== Format Tests ====================
    
    @Nested
    @DisplayName("Format Tests")
    class FormatTests {
        
        @Test
        @DisplayName("Should format message with multiple arguments")
        void testMultipleArguments() {
            if (mockLogger == null) return;
            BukkitLog log = new BukkitLog("TestLog");
            
            log.info("{} + {} = {}", 1, 2, 3);
            verify(mockLogger).info("TestLog: 1 + 2 = 3");
        }
        
        @Test
        @DisplayName("Should handle no arguments")
        void testNoArguments() {
            if (mockLogger == null) return;
            BukkitLog log = new BukkitLog("TestLog");
            
            log.info("Plain message");
            verify(mockLogger).info("TestLog: Plain message");
        }
        
        @Test
        @DisplayName("Should handle null arguments array")
        void testNullArguments() {
            if (mockLogger == null) return;
            BukkitLog log = new BukkitLog("TestLog");
            
            log.info("Message with null args", (Object[]) null);
            verify(mockLogger).info("TestLog: Message with null args");
        }
    }

    // ==================== Throwable Tests ====================
    
    @Nested
    @DisplayName("Throwable Tests")
    class ThrowableTests {
        
        @Test
        @DisplayName("Should log with throwable - trace")
        void testTraceWithThrowable() {
            if (mockLogger == null) return;
            BukkitLog.setLevel(Level.TRACE);
            BukkitLog log = new BukkitLog("TestLog");
            Exception ex = new RuntimeException("Test error");
            
            log.trace(null, ex, "Trace with error");
            verify(mockLogger).finer("TestLog: Trace with error");
        }
        
        @Test
        @DisplayName("Should log with throwable - debug")
        void testDebugWithThrowable() {
            if (mockLogger == null) return;
            BukkitLog.setLevel(Level.DEBUG);
            BukkitLog log = new BukkitLog("TestLog");
            Exception ex = new RuntimeException("Test error");
            
            log.debug(null, ex, "Debug with error");
            verify(mockLogger).fine("TestLog: Debug with error");
        }
        
        @Test
        @DisplayName("Should log with throwable - info")
        void testInfoWithThrowable() {
            if (mockLogger == null) return;
            BukkitLog log = new BukkitLog("TestLog");
            Exception ex = new RuntimeException("Test error");
            
            log.info(null, ex, "Info with error");
            verify(mockLogger).info("TestLog: Info with error");
        }
        
        @Test
        @DisplayName("Should log with throwable - warn")
        void testWarnWithThrowable() {
            if (mockLogger == null) return;
            BukkitLog log = new BukkitLog("TestLog");
            Exception ex = new RuntimeException("Test error");
            
            log.warn(null, ex, "Warn with error");
            verify(mockLogger).warning("TestLog: Warn with error");
        }
        
        @Test
        @DisplayName("Should log with throwable - error")
        void testErrorWithThrowable() {
            if (mockLogger == null) return;
            BukkitLog log = new BukkitLog("TestLog");
            Exception ex = new RuntimeException("Test error");
            
            log.error(null, ex, "Error with throwable");
            verify(mockLogger).severe("TestLog: Error with throwable");
        }
    }
}
