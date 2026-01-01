package com.ultikits.ultitools.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.interfaces.ConfigChangeListener;

/**
 * Tests for configuration change listener functionality.
 */
@DisplayName("ConfigChangeListener Tests")
class ConfigChangeListenerTest {

    private TestConfigEntity config;

    /**
     * Test config entity for testing purposes.
     */
    public static class TestConfigEntity extends AbstractConfigEntity {
        
        @ConfigEntry(path = "testValue")
        private String testValue = "default";
        
        @ConfigEntry(path = "testNumber")
        private int testNumber = 42;
        
        public TestConfigEntity() {
            super("test-config.yml");
        }
        
        public String getTestValue() {
            return testValue;
        }
        
        public void setTestValue(String testValue) {
            this.testValue = testValue;
        }
        
        public int getTestNumber() {
            return testNumber;
        }
        
        public void setTestNumber(int testNumber) {
            this.testNumber = testNumber;
        }
        
        /**
         * Manually trigger listener notification for testing.
         */
        public void triggerNotify() {
            notifyChangeListeners();
        }
    }

    @BeforeEach
    void setUp() {
        config = new TestConfigEntity();
    }

    @Nested
    @DisplayName("Listener Registration Tests")
    class ListenerRegistrationTests {

        @Test
        @DisplayName("should add listener successfully")
        void shouldAddListener() {
            ConfigChangeListener listener = cfg -> {};
            
            config.addChangeListener(listener);
            
            assertEquals(1, config.getChangeListenerCount());
        }

        @Test
        @DisplayName("should add multiple listeners")
        void shouldAddMultipleListeners() {
            config.addChangeListener(cfg -> {});
            config.addChangeListener(cfg -> {});
            config.addChangeListener(cfg -> {});
            
            assertEquals(3, config.getChangeListenerCount());
        }

        @Test
        @DisplayName("should ignore null listener")
        void shouldIgnoreNullListener() {
            config.addChangeListener(null);
            
            assertEquals(0, config.getChangeListenerCount());
        }

        @Test
        @DisplayName("should remove listener successfully")
        void shouldRemoveListener() {
            ConfigChangeListener listener = cfg -> {};
            config.addChangeListener(listener);
            
            config.removeChangeListener(listener);
            
            assertEquals(0, config.getChangeListenerCount());
        }

        @Test
        @DisplayName("should clear all listeners")
        void shouldClearAllListeners() {
            config.addChangeListener(cfg -> {});
            config.addChangeListener(cfg -> {});
            config.addChangeListener(cfg -> {});
            
            config.clearChangeListeners();
            
            assertEquals(0, config.getChangeListenerCount());
        }
    }

    @Nested
    @DisplayName("Listener Notification Tests")
    class ListenerNotificationTests {

        @Test
        @DisplayName("should notify single listener")
        void shouldNotifySingleListener() {
            AtomicBoolean called = new AtomicBoolean(false);
            config.addChangeListener(cfg -> called.set(true));
            
            config.triggerNotify();
            
            assertTrue(called.get());
        }

        @Test
        @DisplayName("should notify all listeners")
        void shouldNotifyAllListeners() {
            AtomicInteger callCount = new AtomicInteger(0);
            
            config.addChangeListener(cfg -> callCount.incrementAndGet());
            config.addChangeListener(cfg -> callCount.incrementAndGet());
            config.addChangeListener(cfg -> callCount.incrementAndGet());
            
            config.triggerNotify();
            
            assertEquals(3, callCount.get());
        }

        @Test
        @DisplayName("should pass config to listener")
        void shouldPassConfigToListener() {
            AtomicReference<AbstractConfigEntity> receivedConfig = new AtomicReference<>();
            config.addChangeListener(receivedConfig::set);
            
            config.triggerNotify();
            
            assertSame(config, receivedConfig.get());
        }

        @Test
        @DisplayName("should continue notifying after listener exception")
        void shouldContinueAfterListenerException() {
            AtomicBoolean firstCalled = new AtomicBoolean(false);
            AtomicBoolean secondCalled = new AtomicBoolean(false);
            AtomicBoolean thirdCalled = new AtomicBoolean(false);
            
            config.addChangeListener(cfg -> firstCalled.set(true));
            config.addChangeListener(cfg -> { throw new RuntimeException("Test exception"); });
            config.addChangeListener(cfg -> thirdCalled.set(true));
            
            // Should not throw
            assertDoesNotThrow(() -> config.triggerNotify());
            
            assertTrue(firstCalled.get(), "First listener should be called");
            assertTrue(thirdCalled.get(), "Third listener should be called despite second throwing");
        }

        @Test
        @DisplayName("should handle multiple exceptions")
        void shouldHandleMultipleExceptions() {
            AtomicInteger callCount = new AtomicInteger(0);
            
            config.addChangeListener(cfg -> { 
                callCount.incrementAndGet();
                throw new RuntimeException("Exception 1"); 
            });
            config.addChangeListener(cfg -> { 
                callCount.incrementAndGet();
                throw new RuntimeException("Exception 2"); 
            });
            config.addChangeListener(cfg -> callCount.incrementAndGet());
            
            assertDoesNotThrow(() -> config.triggerNotify());
            
            assertEquals(3, callCount.get(), "All listeners should be called");
        }
    }

    @Nested
    @DisplayName("Listener Order Tests")
    class ListenerOrderTests {

        @Test
        @DisplayName("should notify listeners in registration order")
        void shouldNotifyInOrder() {
            StringBuilder order = new StringBuilder();
            
            config.addChangeListener(cfg -> order.append("1"));
            config.addChangeListener(cfg -> order.append("2"));
            config.addChangeListener(cfg -> order.append("3"));
            
            config.triggerNotify();
            
            assertEquals("123", order.toString());
        }
    }

    @Nested
    @DisplayName("Thread Safety Tests")
    class ThreadSafetyTests {

        @Test
        @DisplayName("should support concurrent listener addition")
        void shouldSupportConcurrentAddition() throws InterruptedException {
            int threadCount = 10;
            Thread[] threads = new Thread[threadCount];
            
            for (int i = 0; i < threadCount; i++) {
                threads[i] = new Thread(() -> {
                    config.addChangeListener(cfg -> {});
                });
            }
            
            for (Thread thread : threads) {
                thread.start();
            }
            
            for (Thread thread : threads) {
                thread.join();
            }
            
            assertEquals(threadCount, config.getChangeListenerCount());
        }

        @Test
        @DisplayName("should support adding listener during notification")
        void shouldSupportAddingDuringNotification() {
            AtomicInteger callCount = new AtomicInteger(0);
            
            config.addChangeListener(cfg -> {
                callCount.incrementAndGet();
                // Add new listener during notification
                cfg.addChangeListener(c -> callCount.incrementAndGet());
            });
            
            // First notification
            config.triggerNotify();
            assertEquals(1, callCount.get(), "Only original listener called first time");
            
            // Second notification - both listeners should be called
            config.triggerNotify();
            assertEquals(3, callCount.get(), "Both listeners called second time");
        }
    }
}
