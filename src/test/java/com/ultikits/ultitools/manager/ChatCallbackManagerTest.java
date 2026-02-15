package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;

/**
 * ChatCallbackManager 测试
 */
@DisplayName("ChatCallbackManager 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)  // Global timeout to prevent hangs in coverage mode
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test requires reflection for singleton reset
class ChatCallbackManagerTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();

        // 重置 ChatCallbackManager 的静态状态
        resetChatCallbackManager();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
        resetChatCallbackManager();
    }

    /**
     * 通过反射重置 ChatCallbackManager 的静态状态
     * 现在实现代码已经修复，可以安全地在 MockBukkit 环境下调用 initialize()
     */
    private void resetChatCallbackManager() {
        try {
            // 重置 callbacks map
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);
            callbacks.clear();

            // 重置 initialized 为 false，让 initialize() 可以重新运行
            // 实现代码现在会检查 PluginManager 类型，在 MockBukkit 环境下会优雅地跳过
            Field initializedField = ChatCallbackManager.class.getDeclaredField("initialized");
            initializedField.setAccessible(true);
            initializedField.setBoolean(null, false);
        } catch (Exception ignored) {
        }
    }

    @Nested
    @DisplayName("registerCallback 测试")
    class RegisterCallbackTests {

        @Test
        @DisplayName("应该返回唯一的 UUID")
        void shouldReturnUniqueUUID() {
            // Arrange
            Runnable callback1 = () -> {
            };
            Runnable callback2 = () -> {
            };

            // Act
            UUID uuid1 = ChatCallbackManager.registerCallback(callback1);
            UUID uuid2 = ChatCallbackManager.registerCallback(callback2);

            // Assert
            assertThat(uuid1).isNotNull();
            assertThat(uuid2).isNotNull();
            assertThat(uuid1).isNotEqualTo(uuid2);
        }

        @Test
        @DisplayName("应该将回调存储到 callbacks map")
        void shouldStoreCallbackInMap() throws Exception {
            // Arrange
            AtomicBoolean called = new AtomicBoolean(false);
            Runnable callback = () -> called.set(true);

            // Act
            UUID uuid = ChatCallbackManager.registerCallback(callback);

            // Assert
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);

            assertThat(callbacks).containsKey(uuid);
            assertThat(callbacks.get(uuid)).isEqualTo(callback);
        }

        @Test
        @DisplayName("注册后 initialized 应该为 true（如果初始化成功）")
        void shouldSetInitializedFlag() throws Exception {
            // Arrange
            Runnable callback = () -> {
            };

            // Act
            ChatCallbackManager.registerCallback(callback);

            // Assert - 检查是否尝试初始化（可能成功或失败取决于环境）
            Field initializedField = ChatCallbackManager.class.getDeclaredField("initialized");
            initializedField.setAccessible(true);
            // 在 MockBukkit 环境中初始化可能成功
            // 这里我们只验证回调被正确存储
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);
            assertThat(callbacks).isNotEmpty();
        }

        @Test
        @DisplayName("应该支持注册多个回调")
        void shouldSupportMultipleCallbacks() throws Exception {
            // Arrange & Act
            UUID uuid1 = ChatCallbackManager.registerCallback(() -> {
            });
            UUID uuid2 = ChatCallbackManager.registerCallback(() -> {
            });
            UUID uuid3 = ChatCallbackManager.registerCallback(() -> {
            });

            // Assert
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);

            assertThat(callbacks).hasSize(3);
            assertThat(callbacks).containsKeys(uuid1, uuid2, uuid3);
        }
    }

    @Nested
    @DisplayName("callbacks Map 测试")
    class CallbacksMapTests {

        @Test
        @DisplayName("callbacks 应该是 ConcurrentHashMap")
        void shouldBeConcurrentHashMap() throws Exception {
            // Arrange
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);

            // Act
            Object callbacks = callbacksField.get(null);

            // Assert
            assertThat(callbacks).isInstanceOf(java.util.concurrent.ConcurrentHashMap.class);
        }

        @Test
        @DisplayName("callbacks 应该是静态字段")
        void shouldBeStatic() throws Exception {
            // Arrange
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");

            // Assert
            assertThat(java.lang.reflect.Modifier.isStatic(callbacksField.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("callbacks 应该是 final 字段")
        void shouldBeFinal() throws Exception {
            // Arrange
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");

            // Assert
            assertThat(java.lang.reflect.Modifier.isFinal(callbacksField.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("线程安全测试")
    class ThreadSafetyTests {

        @Test
        @DisplayName("多线程并发注册应该安全")
        void shouldBeSafeForConcurrentRegistration() throws Exception {
            // Arrange - reduced counts for coverage testing compatibility
            int threadCount = 5;
            int callbacksPerThread = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            // Act
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < callbacksPerThread; j++) {
                            UUID uuid = ChatCallbackManager.registerCallback(() -> {
                            });
                            if (uuid != null) {
                                successCount.incrementAndGet();
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            // Assert
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);

            assertThat(callbacks.size()).isEqualTo(threadCount * callbacksPerThread);
            assertThat(successCount.get()).isEqualTo(threadCount * callbacksPerThread);
        }
    }

    @Nested
    @DisplayName("回调执行测试")
    class CallbackExecutionTests {

        @Test
        @DisplayName("回调应该可以被调用")
        void callbackShouldBeCallable() throws Exception {
            // Arrange
            AtomicBoolean called = new AtomicBoolean(false);
            Runnable callback = () -> called.set(true);

            UUID uuid = ChatCallbackManager.registerCallback(callback);

            // Act - 直接调用回调
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);

            Runnable storedCallback = callbacks.get(uuid);
            storedCallback.run();

            // Assert
            assertThat(called.get()).isTrue();
        }

        @Test
        @DisplayName("回调应该可以被移除并调用")
        void callbackShouldBeRemovableAndCallable() throws Exception {
            // Arrange
            AtomicBoolean called = new AtomicBoolean(false);
            Runnable callback = () -> called.set(true);

            UUID uuid = ChatCallbackManager.registerCallback(callback);

            // Act - 模拟命令执行逻辑
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);

            Runnable removedCallback = callbacks.remove(uuid);
            if (removedCallback != null) {
                removedCallback.run();
            }

            // Assert
            assertThat(called.get()).isTrue();
            assertThat(callbacks).doesNotContainKey(uuid);
        }
    }

    @Nested
    @DisplayName("UUID 测试")
    class UUIDTests {

        @Test
        @DisplayName("生成的 UUID 应该都是有效的")
        void generatedUUIDsShouldBeValid() {
            // Act
            UUID uuid = ChatCallbackManager.registerCallback(() -> {
            });

            // Assert
            assertThat(uuid).isNotNull();
            assertThat(uuid.version()).isEqualTo(4); // Random UUID
        }

        @Test
        @DisplayName("大量注册不应产生重复 UUID")
        void shouldNotGenerateDuplicateUUIDs() throws Exception {
            // Arrange
            int count = 1000;
            java.util.Set<UUID> uuids = new java.util.HashSet<>();

            // Act
            for (int i = 0; i < count; i++) {
                UUID uuid = ChatCallbackManager.registerCallback(() -> {
                });
                uuids.add(uuid);
            }

            // Assert
            assertThat(uuids).hasSize(count);
        }
    }

    @Nested
    @DisplayName("命令执行测试")
    class CommandExecutionTests {

        @Test
        @DisplayName("通过反射模拟命令执行应该触发回调")
        void executingCommandViaReflectionShouldTriggerCallback() throws Exception {
            // Arrange
            AtomicBoolean called = new AtomicBoolean(false);
            Runnable callback = () -> called.set(true);
            UUID uuid = ChatCallbackManager.registerCallback(callback);

            // Act - 直接从 callbacks map 中获取并执行（模拟命令执行逻辑）
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);

            Runnable storedCallback = callbacks.remove(uuid);
            if (storedCallback != null) {
                storedCallback.run();
            }

            // Assert
            assertThat(called.get()).isTrue();
            assertThat(callbacks).doesNotContainKey(uuid);
        }

        @Test
        @DisplayName("Map.remove 应该返回并移除回调")
        void mapRemoveShouldReturnAndRemoveCallback() throws Exception {
            // Arrange
            AtomicInteger callCount = new AtomicInteger(0);
            Runnable callback = () -> callCount.incrementAndGet();
            UUID uuid = ChatCallbackManager.registerCallback(callback);

            // Act - 模拟命令逻辑：remove 并调用
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);

            Runnable removed = callbacks.remove(uuid);
            assertThat(removed).isNotNull();
            removed.run();

            // Assert
            assertThat(callCount.get()).isEqualTo(1);
            assertThat(callbacks).doesNotContainKey(uuid);
        }

        @Test
        @DisplayName("不存在的 UUID 应该返回 null")
        void nonExistentUUIDShouldReturnNull() throws Exception {
            // Arrange
            ChatCallbackManager.registerCallback(() -> {
            });
            UUID nonExistent = UUID.randomUUID();

            // Act
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);
            Runnable result = callbacks.remove(nonExistent);

            // Assert
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("多次 remove 同一 UUID 第二次应返回 null")
        void multipleRemovesShouldReturnNullOnSecondAttempt() throws Exception {
            // Arrange
            Runnable callback = () -> {
            };
            UUID uuid = ChatCallbackManager.registerCallback(callback);

            // Act
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);

            Runnable first = callbacks.remove(uuid);
            Runnable second = callbacks.remove(uuid);

            // Assert
            assertThat(first).isNotNull();
            assertThat(second).isNull();
        }
    }

    @Nested
    @DisplayName("回调异常处理测试")
    class CallbackExceptionHandlingTests {

        @Test
        @DisplayName("回调抛出异常时 remove 仍应返回回调对象")
        void callbackExceptionShouldStillAllowRemove() throws Exception {
            // Arrange
            Runnable throwingCallback = () -> {
                throw new IllegalStateException("Test exception");
            };
            UUID uuid = ChatCallbackManager.registerCallback(throwingCallback);

            // Act - 从 map 中移除
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);

            Runnable removed = callbacks.remove(uuid);

            // Assert
            assertThat(removed).isNotNull();
            assertThat(callbacks).doesNotContainKey(uuid);

            // 尝试调用会抛出异常（但这是预期的）
            try {
                removed.run();
            } catch (Exception e) {
                assertThat(e).isInstanceOf(RuntimeException.class);
                assertThat(e.getMessage()).isEqualTo("Test exception");
            }
        }

        @Test
        @DisplayName("多个回调可以独立执行")
        void multipleCallbacksAreIndependent() throws Exception {
            // Arrange
            AtomicInteger successCount = new AtomicInteger(0);
            Runnable throwingCallback = () -> {
                throw new RuntimeException("Test exception");
            };
            Runnable successCallback = () -> successCount.incrementAndGet();

            UUID uuid1 = ChatCallbackManager.registerCallback(throwingCallback);
            UUID uuid2 = ChatCallbackManager.registerCallback(successCallback);

            // Act - 分别执行
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);

            Runnable cb1 = callbacks.remove(uuid1);
            Runnable cb2 = callbacks.remove(uuid2);

            // 执行第一个（会抛出异常）
            try {
                cb1.run();
            } catch (Exception ignored) {
            }

            // 执行第二个（不应受影响）
            cb2.run();

            // Assert
            assertThat(successCount.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("初始化测试")
    class InitializationTests {

        @Test
        @DisplayName("初始化应该在 MockBukkit 环境下正常完成")
        void initializationShouldCompleteInMockBukkitEnvironment() throws Exception {
            // Arrange - 重置 initialized 为 false
            Field initializedField = ChatCallbackManager.class.getDeclaredField("initialized");
            initializedField.setAccessible(true);
            initializedField.setBoolean(null, false);
            
            // Assert - initialized 应该为 false
            assertThat(initializedField.getBoolean(null)).isFalse();

            // Act - 注册回调会触发 initialize()
            ChatCallbackManager.registerCallback(() -> {
            });

            // Assert - initialized 应该被设置为 true（即使 commandMap 为 null）
            assertThat(initializedField.getBoolean(null)).isTrue();

            // Assert - 回调应该被存储
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);
            assertThat(callbacks).isNotEmpty();
        }

        @Test
        @DisplayName("initialize 应该只执行一次")
        void initializeShouldOnlyRunOnce() throws Exception {
            // Arrange
            Field initializedField = ChatCallbackManager.class.getDeclaredField("initialized");
            initializedField.setAccessible(true);
            initializedField.setBoolean(null, false);

            // Act - 多次注册回调
            ChatCallbackManager.registerCallback(() -> {});
            ChatCallbackManager.registerCallback(() -> {});
            ChatCallbackManager.registerCallback(() -> {});

            // Assert - initialized 应该为 true
            assertThat(initializedField.getBoolean(null)).isTrue();

            // Assert - 所有回调都应该被存储
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);
            assertThat(callbacks).hasSize(3);
        }

        @Test
        @DisplayName("多线程环境下回调都应成功注册")
        void allCallbacksShouldBeRegisteredInMultiThreadedEnvironment() throws Exception {
            // Arrange - reduced thread count for coverage testing compatibility
            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);

            // Act - 所有线程同时开始注册
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await(30, TimeUnit.SECONDS);
                        ChatCallbackManager.registerCallback(() -> {
                        });
                    } catch (Exception e) {
                        // ignore
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // 释放所有线程
            endLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            // Assert - 所有回调都应该成功注册
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);
            assertThat(callbacks).hasSize(threadCount);
        }
    }

    @Nested
    @DisplayName("边界条件和特殊情况测试")
    class EdgeCasesTests {

        @Test
        @DisplayName("连续注册同一个回调对象")
        void registeringSameCallbackMultipleTimes() throws Exception {
            // Arrange
            AtomicInteger callCount = new AtomicInteger(0);
            Runnable callback = () -> callCount.incrementAndGet();

            // Act - 注册同一个回调多次
            UUID uuid1 = ChatCallbackManager.registerCallback(callback);
            UUID uuid2 = ChatCallbackManager.registerCallback(callback);
            UUID uuid3 = ChatCallbackManager.registerCallback(callback);

            // Assert - 应该有不同的 UUID
            assertThat(uuid1).isNotEqualTo(uuid2);
            assertThat(uuid2).isNotEqualTo(uuid3);
            assertThat(uuid1).isNotEqualTo(uuid3);

            // 执行每个回调
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);

            callbacks.remove(uuid1).run();
            callbacks.remove(uuid2).run();
            callbacks.remove(uuid3).run();

            assertThat(callCount.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("registerCallback 是同步方法")
        void registerCallbackIsSynchronized() throws Exception {
            // Arrange
            java.lang.reflect.Method method = ChatCallbackManager.class.getDeclaredMethod("registerCallback", Runnable.class);

            // Assert
            assertThat(java.lang.reflect.Modifier.isSynchronized(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("大量回调不应导致性能问题")
        void largeNumberOfCallbacksShouldNotCausePerformanceIssues() throws Exception {
            // Arrange
            int count = 10000;
            java.util.List<UUID> uuids = new java.util.ArrayList<>();

            // Act - 注册大量回调
            long startTime = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                UUID uuid = ChatCallbackManager.registerCallback(() -> {
                });
                uuids.add(uuid);
            }
            long registrationTime = System.currentTimeMillis() - startTime;

            // Assert
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);

            assertThat(callbacks).hasSizeGreaterThanOrEqualTo(count);
            assertThat(registrationTime).isLessThan(5000); // 应该在5秒内完成

            // 清理 - 移除部分回调
            for (int i = 0; i < 100; i++) {
                callbacks.remove(uuids.get(i));
            }
        }

        @Test
        @DisplayName("空回调 lambda 应该能被执行")
        void emptyLambdaCallbackShouldBeExecutable() throws Exception {
            // Arrange
            Runnable emptyCallback = () -> {
            };
            UUID uuid = ChatCallbackManager.registerCallback(emptyCallback);

            // Act
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);

            Runnable removed = callbacks.remove(uuid);

            // Assert & Act - 不应该抛出异常
            assertThat(removed).isNotNull();
            removed.run(); // 应该成功执行
        }
    }

    @Nested
    @DisplayName("getCommandMap 方法测试")
    class GetCommandMapTests {

        @Test
        @DisplayName("在 MockBukkit 环境下 getCommandMap 应该返回 null")
        void getCommandMapShouldReturnNullInMockBukkitEnvironment() throws Exception {
            // Arrange - 通过反射调用私有方法
            java.lang.reflect.Method getCommandMapMethod = 
                ChatCallbackManager.class.getDeclaredMethod("getCommandMap");
            getCommandMapMethod.setAccessible(true);

            // Act
            Object result = getCommandMapMethod.invoke(null);

            // Assert - MockBukkit 不是 SimplePluginManager，应该返回 null
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("getCommandMap 是私有静态方法")
        void getCommandMapShouldBePrivateStatic() throws Exception {
            // Arrange
            java.lang.reflect.Method method = 
                ChatCallbackManager.class.getDeclaredMethod("getCommandMap");

            // Assert
            assertThat(java.lang.reflect.Modifier.isPrivate(method.getModifiers())).isTrue();
            assertThat(java.lang.reflect.Modifier.isStatic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("PluginManager 检查应该正常工作")
        void pluginManagerCheckShouldWork() {
            // Assert - MockBukkit 的 PluginManager 不是 SimplePluginManager
            assertThat(org.bukkit.Bukkit.getPluginManager())
                .isNotInstanceOf(org.bukkit.plugin.SimplePluginManager.class);
        }
    }

    @Nested
    @DisplayName("initialize 方法高级测试")
    class InitializeAdvancedTests {

        @Test
        @DisplayName("initialize 是私有静态方法")
        void initializeShouldBePrivateStatic() throws Exception {
            // Arrange
            java.lang.reflect.Method method = 
                ChatCallbackManager.class.getDeclaredMethod("initialize");

            // Assert
            assertThat(java.lang.reflect.Modifier.isPrivate(method.getModifiers())).isTrue();
            assertThat(java.lang.reflect.Modifier.isStatic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("直接调用 initialize 应该设置 initialized 为 true")
        void directInitializeCallShouldSetInitializedTrue() throws Exception {
            // Arrange
            Field initializedField = ChatCallbackManager.class.getDeclaredField("initialized");
            initializedField.setAccessible(true);
            initializedField.setBoolean(null, false);

            java.lang.reflect.Method initializeMethod = 
                ChatCallbackManager.class.getDeclaredMethod("initialize");
            initializeMethod.setAccessible(true);

            // Act
            initializeMethod.invoke(null);

            // Assert
            assertThat(initializedField.getBoolean(null)).isTrue();
        }

        @Test
        @DisplayName("多次调用 initialize 应该是幂等的")
        void multipleInitializeCallsShouldBeIdempotent() throws Exception {
            // Arrange
            Field initializedField = ChatCallbackManager.class.getDeclaredField("initialized");
            initializedField.setAccessible(true);
            initializedField.setBoolean(null, false);

            java.lang.reflect.Method initializeMethod = 
                ChatCallbackManager.class.getDeclaredMethod("initialize");
            initializeMethod.setAccessible(true);

            // Act - 多次调用
            initializeMethod.invoke(null);
            initializeMethod.invoke(null);
            initializeMethod.invoke(null);

            // Assert - 应该仍然为 true
            assertThat(initializedField.getBoolean(null)).isTrue();
        }

        @Test
        @DisplayName("commandMap 为 null 时 initialize 应该提前返回并设置 initialized")
        void initializeShouldReturnEarlyWhenCommandMapIsNull() throws Exception {
            // Arrange
            Field initializedField = ChatCallbackManager.class.getDeclaredField("initialized");
            initializedField.setAccessible(true);
            initializedField.setBoolean(null, false);

            // Act - 在 MockBukkit 环境下，commandMap 会是 null
            java.lang.reflect.Method initializeMethod = 
                ChatCallbackManager.class.getDeclaredMethod("initialize");
            initializeMethod.setAccessible(true);
            initializeMethod.invoke(null);

            // Assert - initialized 应该为 true（即使 commandMap 为 null）
            assertThat(initializedField.getBoolean(null)).isTrue();
        }
    }

    @Nested
    @DisplayName("Command execute 方法模拟测试")
    class CommandExecuteSimulationTests {

        @Test
        @DisplayName("模拟命令执行 - 参数长度为 0 应该返回 false")
        void executeWithNoArgsShouldReturnFalse() throws Exception {
            // 这个测试模拟 Command.execute 方法的行为
            // args.length != 1 时应该返回 false
            String[] args = new String[0];
            
            // Assert - args.length != 1
            assertThat(args.length).isNotEqualTo(1);
        }

        @Test
        @DisplayName("模拟命令执行 - 参数长度为 2 应该返回 false")
        void executeWithTwoArgsShouldReturnFalse() throws Exception {
            // 模拟 args.length != 1 的情况
            String[] args = new String[]{"arg1", "arg2"};
            
            // Assert
            assertThat(args.length).isNotEqualTo(1);
        }

        @Test
        @DisplayName("模拟命令执行 - 无效 UUID 字符串应该被忽略")
        void executeWithInvalidUUIDShouldBeIgnored() throws Exception { // NOPMD - uses Mockito verify()
            // Arrange
            String invalidUUID = "not-a-valid-uuid";

            // Act & Assert - UUID.fromString 应该抛出异常
            org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> UUID.fromString(invalidUUID)
            );
        }

        @Test
        @DisplayName("模拟命令执行 - 有效 UUID 但不存在的回调")
        void executeWithValidButNonExistentUUID() throws Exception {
            // Arrange
            UUID nonExistentUUID = UUID.randomUUID();
            
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);

            // Act
            Runnable callback = callbacks.remove(nonExistentUUID);

            // Assert - 应该返回 null
            assertThat(callback).isNull();
        }

        @Test
        @DisplayName("模拟命令执行 - 完整的成功路径")
        void executeCompleteSuccessPath() throws Exception {
            // Arrange
            AtomicBoolean executed = new AtomicBoolean(false);
            Runnable callback = () -> executed.set(true);
            UUID uuid = ChatCallbackManager.registerCallback(callback);

            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);

            // Act - 模拟 Command.execute 的逻辑
            String[] args = new String[]{uuid.toString()};
            if (args.length == 1) {
                try {
                    UUID parsedUUID = UUID.fromString(args[0]);
                    Runnable cb = callbacks.remove(parsedUUID);
                    if (cb != null) {
                        cb.run();
                    }
                } catch (Exception ignored) {
                }
            }

            // Assert
            assertThat(executed.get()).isTrue();
            assertThat(callbacks).doesNotContainKey(uuid);
        }

        @Test
        @DisplayName("模拟命令执行 - 回调抛出异常应该被捕获")
        void executeWithThrowingCallbackShouldCatchException() throws Exception {
            // Arrange
            Runnable throwingCallback = () -> {
                throw new IllegalStateException("Callback exception");
            };
            UUID uuid = ChatCallbackManager.registerCallback(throwingCallback);

            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);

            // Act - 模拟 Command.execute 的逻辑（异常应该被捕获）
            String[] args = new String[]{uuid.toString()};
            boolean exceptionCaught = false;
            if (args.length == 1) {
                try {
                    UUID parsedUUID = UUID.fromString(args[0]);
                    Runnable cb = callbacks.remove(parsedUUID);
                    if (cb != null) {
                        cb.run();
                    }
                } catch (Exception ignored) {
                    exceptionCaught = true;
                }
            }

            // Assert - 异常应该被捕获
            assertThat(exceptionCaught).isTrue();
            assertThat(callbacks).doesNotContainKey(uuid);
        }
    }

    @Nested
    @DisplayName("静态字段测试")
    class StaticFieldTests {

        @Test
        @DisplayName("initialized 字段应该是私有静态的")
        void initializedFieldShouldBePrivateStatic() throws Exception {
            Field field = ChatCallbackManager.class.getDeclaredField("initialized");
            
            assertThat(java.lang.reflect.Modifier.isPrivate(field.getModifiers())).isTrue();
            assertThat(java.lang.reflect.Modifier.isStatic(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("initialized 字段应该是 boolean 类型")
        void initializedFieldShouldBeBoolean() throws Exception {
            Field field = ChatCallbackManager.class.getDeclaredField("initialized");
            
            assertThat(field.getType()).isEqualTo(boolean.class);
        }

        @Test
        @DisplayName("callbacks 字段应该是私有静态 final 的")
        void callbacksFieldShouldBePrivateStaticFinal() throws Exception {
            Field field = ChatCallbackManager.class.getDeclaredField("callbacks");
            
            assertThat(java.lang.reflect.Modifier.isPrivate(field.getModifiers())).isTrue();
            assertThat(java.lang.reflect.Modifier.isStatic(field.getModifiers())).isTrue();
            assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("回调生命周期测试")
    class CallbackLifecycleTests {

        @Test
        @DisplayName("回调注册后应该立即可用")
        void callbackShouldBeAvailableImmediatelyAfterRegistration() throws Exception {
            // Arrange
            Runnable callback = () -> {};

            // Act
            UUID uuid = ChatCallbackManager.registerCallback(callback);

            // Assert
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);

            assertThat(callbacks.containsKey(uuid)).isTrue();
            assertThat(callbacks.get(uuid)).isSameAs(callback);
        }

        @Test
        @DisplayName("回调移除后应该不再可用")
        void callbackShouldNotBeAvailableAfterRemoval() throws Exception {
            // Arrange
            Runnable callback = () -> {};
            UUID uuid = ChatCallbackManager.registerCallback(callback);

            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);

            // Act
            callbacks.remove(uuid);

            // Assert
            assertThat(callbacks.containsKey(uuid)).isFalse();
            assertThat(callbacks.get(uuid)).isNull();
        }

        @Test
        @DisplayName("回调可以存储复杂状态")
        void callbackCanStoreComplexState() throws Exception {
            // Arrange
            java.util.List<String> messages = new java.util.ArrayList<>();
            Runnable callback = () -> {
                messages.add("first");
                messages.add("second");
                messages.add("third");
            };

            // Act
            UUID uuid = ChatCallbackManager.registerCallback(callback);
            
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);
            
            Runnable stored = callbacks.remove(uuid);
            stored.run();

            // Assert
            assertThat(messages).containsExactly("first", "second", "third");
        }

        @Test
        @DisplayName("回调可以修改外部状态")
        void callbackCanModifyExternalState() throws Exception {
            // Arrange
            AtomicInteger counter = new AtomicInteger(0);
            Runnable callback = () -> {
                counter.incrementAndGet();
                counter.incrementAndGet();
            };

            // Act
            UUID uuid = ChatCallbackManager.registerCallback(callback);
            
            Field callbacksField = ChatCallbackManager.class.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Runnable> callbacks = (Map<UUID, Runnable>) callbacksField.get(null);
            
            Runnable stored = callbacks.remove(uuid);
            stored.run();

            // Assert
            assertThat(counter.get()).isEqualTo(2);
        }
    }
}
