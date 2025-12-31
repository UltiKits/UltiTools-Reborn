package com.ultikits.ultitools.interfaces;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link Cached} interface.
 */
@DisplayName("Cached Interface Tests")
class CachedTest {

    @Nested
    @DisplayName("Interface Structure Tests")
    class InterfaceStructureTests {

        @Test
        @DisplayName("Should be an interface")
        void shouldBeInterface() {
            assertThat(Cached.class.isInterface()).isTrue();
        }

        @Test
        @DisplayName("Should have flush method")
        void shouldHaveFlushMethod() throws NoSuchMethodException {
            Method flushMethod = Cached.class.getMethod("flush");
            assertThat(flushMethod).isNotNull();
            assertThat(Modifier.isPublic(flushMethod.getModifiers())).isTrue();
            assertThat(Modifier.isAbstract(flushMethod.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("Should have gc method")
        void shouldHaveGcMethod() throws NoSuchMethodException {
            Method gcMethod = Cached.class.getMethod("gc");
            assertThat(gcMethod).isNotNull();
            assertThat(Modifier.isPublic(gcMethod.getModifiers())).isTrue();
            assertThat(Modifier.isAbstract(gcMethod.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("flush method should return void")
        void flushMethodShouldReturnVoid() throws NoSuchMethodException {
            Method flushMethod = Cached.class.getMethod("flush");
            assertThat(flushMethod.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("gc method should return void")
        void gcMethodShouldReturnVoid() throws NoSuchMethodException {
            Method gcMethod = Cached.class.getMethod("gc");
            assertThat(gcMethod.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("flush method should have no parameters")
        void flushMethodShouldHaveNoParameters() throws NoSuchMethodException {
            Method flushMethod = Cached.class.getMethod("flush");
            assertThat(flushMethod.getParameterCount()).isZero();
        }

        @Test
        @DisplayName("gc method should have no parameters")
        void gcMethodShouldHaveNoParameters() throws NoSuchMethodException {
            Method gcMethod = Cached.class.getMethod("gc");
            assertThat(gcMethod.getParameterCount()).isZero();
        }

        @Test
        @DisplayName("Should have exactly 2 methods")
        void shouldHaveExactlyTwoMethods() {
            Method[] methods = Cached.class.getDeclaredMethods();
            assertThat(methods).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Implementation Tests")
    class ImplementationTests {

        @Test
        @DisplayName("Simple implementation should work")
        void simpleImplementationShouldWork() {
            AtomicBoolean flushed = new AtomicBoolean(false);
            AtomicBoolean gcCalled = new AtomicBoolean(false);

            Cached cached = new Cached() {
                @Override
                public void flush() {
                    flushed.set(true);
                }

                @Override
                public void gc() {
                    gcCalled.set(true);
                }
            };

            assertThat(flushed.get()).isFalse();
            assertThat(gcCalled.get()).isFalse();

            cached.flush();
            assertThat(flushed.get()).isTrue();

            cached.gc();
            assertThat(gcCalled.get()).isTrue();
        }

        @Test
        @DisplayName("Multiple flush calls should work")
        void multipleFlushCallsShouldWork() {
            AtomicInteger flushCount = new AtomicInteger(0);

            Cached cached = new Cached() {
                @Override
                public void flush() {
                    flushCount.incrementAndGet();
                }

                @Override
                public void gc() {
                }
            };

            cached.flush();
            cached.flush();
            cached.flush();

            assertThat(flushCount.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("Multiple gc calls should work")
        void multipleGcCallsShouldWork() {
            AtomicInteger gcCount = new AtomicInteger(0);

            Cached cached = new Cached() {
                @Override
                public void flush() {
                }

                @Override
                public void gc() {
                    gcCount.incrementAndGet();
                }
            };

            cached.gc();
            cached.gc();
            cached.gc();

            assertThat(gcCount.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("Flush and gc can be called in any order")
        void flushAndGcCanBeCalledInAnyOrder() {
            StringBuilder order = new StringBuilder();

            Cached cached = new Cached() {
                @Override
                public void flush() {
                    order.append("F");
                }

                @Override
                public void gc() {
                    order.append("G");
                }
            };

            cached.flush();
            cached.gc();
            cached.flush();
            cached.gc();
            cached.gc();
            cached.flush();

            assertThat(order.toString()).isEqualTo("FGFGGF");
        }
    }

    @Nested
    @DisplayName("Cache Behavior Pattern Tests")
    class CacheBehaviorPatternTests {

        @Test
        @DisplayName("Should demonstrate typical cache flush pattern")
        void shouldDemonstrateTypicalCacheFlushPattern() {
            // Simulates a simple cache that persists data on flush
            class SimpleCache implements Cached {
                private String cachedData = "";
                private String persistedData = "";
                private boolean dirty = false;

                public void set(String data) {
                    this.cachedData = data;
                    this.dirty = true;
                }

                public String getCached() {
                    return cachedData;
                }

                public String getPersisted() {
                    return persistedData;
                }

                @Override
                public void flush() {
                    if (dirty) {
                        persistedData = cachedData;
                        dirty = false;
                    }
                }

                @Override
                public void gc() {
                    // Clear cache that matches persisted data
                    if (cachedData.equals(persistedData)) {
                        cachedData = "";
                    }
                }
            }

            SimpleCache cache = new SimpleCache();

            // Initially both are empty
            assertThat(cache.getCached()).isEmpty();
            assertThat(cache.getPersisted()).isEmpty();

            // Set data - only cached, not persisted
            cache.set("Hello World");
            assertThat(cache.getCached()).isEqualTo("Hello World");
            assertThat(cache.getPersisted()).isEmpty();

            // Flush - now persisted
            cache.flush();
            assertThat(cache.getCached()).isEqualTo("Hello World");
            assertThat(cache.getPersisted()).isEqualTo("Hello World");

            // GC - clears cache since it matches persisted
            cache.gc();
            assertThat(cache.getCached()).isEmpty();
            assertThat(cache.getPersisted()).isEqualTo("Hello World");
        }

        @Test
        @DisplayName("Should demonstrate gc not clearing dirty cache")
        void shouldDemonstrateGcNotClearingDirtyCache() {
            class DirtyCache implements Cached {
                private String cachedData = "";
                private String persistedData = "";

                public void set(String data) {
                    this.cachedData = data;
                }

                public String getCached() {
                    return cachedData;
                }

                @Override
                public void flush() {
                    persistedData = cachedData;
                }

                @Override
                public void gc() {
                    // Only clear if cache matches persisted
                    if (cachedData.equals(persistedData)) {
                        cachedData = "";
                    }
                }
            }

            DirtyCache cache = new DirtyCache();
            cache.set("New Data");

            // GC without flush - cache should NOT be cleared
            cache.gc();
            assertThat(cache.getCached()).isEqualTo("New Data");

            // Flush then GC - cache should be cleared
            cache.flush();
            cache.gc();
            assertThat(cache.getCached()).isEmpty();
        }
    }
}
