package com.ultikits.ultitools.interfaces;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@link IPlugin} interface.
 */
@DisplayName("IPlugin Interface Tests")
class IPluginTest {

    @Nested
    @DisplayName("Interface Structure Tests")
    class InterfaceStructureTests {

        @Test
        @DisplayName("Should be an interface")
        void shouldBeInterface() {
            assertThat(IPlugin.class.isInterface()).isTrue();
        }

        @Test
        @DisplayName("Should have registerSelf method")
        void shouldHaveRegisterSelfMethod() throws NoSuchMethodException {
            Method method = IPlugin.class.getMethod("registerSelf");
            assertThat(method).isNotNull();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(Modifier.isAbstract(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("Should have unregisterSelf method")
        void shouldHaveUnregisterSelfMethod() throws NoSuchMethodException {
            Method method = IPlugin.class.getMethod("unregisterSelf");
            assertThat(method).isNotNull();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(Modifier.isAbstract(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("Should have reloadSelf method")
        void shouldHaveReloadSelfMethod() throws NoSuchMethodException {
            Method method = IPlugin.class.getMethod("reloadSelf");
            assertThat(method).isNotNull();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(Modifier.isAbstract(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("registerSelf should return boolean")
        void registerSelfShouldReturnBoolean() throws NoSuchMethodException {
            Method method = IPlugin.class.getMethod("registerSelf");
            assertThat(method.getReturnType()).isEqualTo(boolean.class);
        }

        @Test
        @DisplayName("unregisterSelf should return void")
        void unregisterSelfShouldReturnVoid() throws NoSuchMethodException {
            Method method = IPlugin.class.getMethod("unregisterSelf");
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("reloadSelf should return void")
        void reloadSelfShouldReturnVoid() throws NoSuchMethodException {
            Method method = IPlugin.class.getMethod("reloadSelf");
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("registerSelf should declare IOException")
        void registerSelfShouldDeclareIOException() throws NoSuchMethodException {
            Method method = IPlugin.class.getMethod("registerSelf");
            Class<?>[] exceptionTypes = method.getExceptionTypes();
            assertThat(exceptionTypes).contains(IOException.class);
        }

        @Test
        @DisplayName("Should have exactly 3 methods")
        void shouldHaveExactlyThreeMethods() {
            Method[] methods = IPlugin.class.getDeclaredMethods();
            assertThat(methods).hasSize(3);
        }
    }

    @Nested
    @DisplayName("Implementation Tests")
    class ImplementationTests {

        @Test
        @DisplayName("Simple implementation should work")
        void simpleImplementationShouldWork() throws IOException {
            AtomicBoolean registered = new AtomicBoolean(false);
            AtomicBoolean unregistered = new AtomicBoolean(false);
            AtomicBoolean reloaded = new AtomicBoolean(false);

            IPlugin plugin = new IPlugin() {
                @Override
                public boolean registerSelf() {
                    registered.set(true);
                    return true;
                }

                @Override
                public void unregisterSelf() {
                    unregistered.set(true);
                }

                @Override
                public void reloadSelf() {
                    reloaded.set(true);
                }
            };

            boolean result = plugin.registerSelf();
            assertThat(result).isTrue();
            assertThat(registered.get()).isTrue();

            plugin.unregisterSelf();
            assertThat(unregistered.get()).isTrue();

            plugin.reloadSelf();
            assertThat(reloaded.get()).isTrue();
        }

        @Test
        @DisplayName("registerSelf can return false")
        void registerSelfCanReturnFalse() throws IOException {
            IPlugin plugin = new IPlugin() {
                @Override
                public boolean registerSelf() {
                    return false;
                }

                @Override
                public void unregisterSelf() {
                    // Empty implementation for test - method intentionally does nothing
                }

                @Override
                public void reloadSelf() {
                    // Empty implementation for test - method intentionally does nothing
                }
            };

            assertThat(plugin.registerSelf()).isFalse();
        }

        @Test
        @DisplayName("registerSelf can throw IOException")
        void registerSelfCanThrowIOException() {
            IPlugin plugin = new IPlugin() {
                @Override
                public boolean registerSelf() throws IOException {
                    throw new IOException("Failed to load config");
                }

                @Override
                public void unregisterSelf() {
                    // Empty implementation for test - method intentionally does nothing
                }

                @Override
                public void reloadSelf() {
                    // Empty implementation for test - method intentionally does nothing
                }
            };

            assertThatThrownBy(plugin::registerSelf)
                    .isInstanceOf(IOException.class)
                    .hasMessage("Failed to load config");
        }
    }

    @Nested
    @DisplayName("Lifecycle Tests")
    class LifecycleTests {

        @Test
        @DisplayName("Should support full lifecycle - register, reload, unregister")
        void shouldSupportFullLifecycle() throws IOException {
            StringBuilder lifecycle = new StringBuilder();

            IPlugin plugin = new IPlugin() {
                @Override
                public boolean registerSelf() {
                    lifecycle.append("R");
                    return true;
                }

                @Override
                public void unregisterSelf() {
                    lifecycle.append("U");
                }

                @Override
                public void reloadSelf() {
                    lifecycle.append("L");
                }
            };

            plugin.registerSelf();
            plugin.reloadSelf();
            plugin.reloadSelf();
            plugin.unregisterSelf();

            assertThat(lifecycle.toString()).isEqualTo("RLLU");
        }

        @Test
        @DisplayName("Multiple reloads should work")
        void multipleReloadsShouldWork() throws IOException {
            AtomicInteger reloadCount = new AtomicInteger(0);

            IPlugin plugin = new IPlugin() {
                @Override
                public boolean registerSelf() {
                    return true;
                }

                @Override
                public void unregisterSelf() {
                }

                @Override
                public void reloadSelf() {
                    reloadCount.incrementAndGet();
                }
            };

            plugin.registerSelf();
            for (int i = 0; i < 5; i++) {
                plugin.reloadSelf();
            }

            assertThat(reloadCount.get()).isEqualTo(5);
        }

        @Test
        @DisplayName("Reload does not require re-registration")
        void reloadDoesNotRequireReRegistration() throws IOException {
            AtomicInteger registerCount = new AtomicInteger(0);
            AtomicInteger unregisterCount = new AtomicInteger(0);
            AtomicInteger reloadCount = new AtomicInteger(0);

            IPlugin plugin = new IPlugin() {
                @Override
                public boolean registerSelf() {
                    registerCount.incrementAndGet();
                    return true;
                }

                @Override
                public void unregisterSelf() {
                    unregisterCount.incrementAndGet();
                }

                @Override
                public void reloadSelf() {
                    // Reload should NOT call register or unregister
                    reloadCount.incrementAndGet();
                }
            };

            plugin.registerSelf();
            plugin.reloadSelf();
            plugin.reloadSelf();
            plugin.unregisterSelf();

            assertThat(registerCount.get()).isEqualTo(1);
            assertThat(unregisterCount.get()).isEqualTo(1);
            assertThat(reloadCount.get()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Resource Management Tests")
    class ResourceManagementTests {

        @Test
        @DisplayName("Should handle resource initialization in registerSelf")
        void shouldHandleResourceInitializationInRegisterSelf() throws IOException {
            class ResourcePlugin implements IPlugin {
                private boolean resourcesInitialized = false;
                private boolean resourcesCleaned = false;

                @Override
                public boolean registerSelf() {
                    // Simulate resource initialization
                    resourcesInitialized = true;
                    return true;
                }

                @Override
                public void unregisterSelf() {
                    // Simulate resource cleanup
                    resourcesCleaned = true;
                }

                @Override
                public void reloadSelf() {
                    // Reload resources without full cleanup
                }

                public boolean isResourcesInitialized() {
                    return resourcesInitialized;
                }

                public boolean isResourcesCleaned() {
                    return resourcesCleaned;
                }
            }

            ResourcePlugin plugin = new ResourcePlugin();
            assertThat(plugin.isResourcesInitialized()).isFalse();
            assertThat(plugin.isResourcesCleaned()).isFalse();

            plugin.registerSelf();
            assertThat(plugin.isResourcesInitialized()).isTrue();
            assertThat(plugin.isResourcesCleaned()).isFalse();

            plugin.unregisterSelf();
            assertThat(plugin.isResourcesCleaned()).isTrue();
        }

        @Test
        @DisplayName("Should return false if initialization fails")
        void shouldReturnFalseIfInitializationFails() throws IOException {
            IPlugin plugin = new IPlugin() {
                @Override
                public boolean registerSelf() {
                    // Simulate failed initialization
                    return false;
                }

                @Override
                public void unregisterSelf() {
                }

                @Override
                public void reloadSelf() {
                }
            };

            assertThat(plugin.registerSelf()).isFalse();
        }
    }
}
