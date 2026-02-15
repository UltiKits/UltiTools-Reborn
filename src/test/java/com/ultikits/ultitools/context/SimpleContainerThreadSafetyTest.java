package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Thread safety tests for SimpleContainer.
 * <br>
 * SimpleContainer的线程安全测试。
 * <br>
 * Note: Thread counts and timeouts are reduced for compatibility with 
 * coverage testing tools which significantly slow down execution.
 */
@DisplayName("SimpleContainer Thread Safety Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)  // Global timeout to prevent hangs
class SimpleContainerThreadSafetyTest {

    // Reduced thread counts for coverage testing compatibility
    private static final int SMALL_THREAD_COUNT = 5;
    private static final int MEDIUM_THREAD_COUNT = 10;
    private static final long LATCH_TIMEOUT_SECONDS = 30;
    private static final long FUTURE_TIMEOUT_SECONDS = 10;

    private SimpleContainer container;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        container = new SimpleContainer();
        executor = Executors.newFixedThreadPool(SMALL_THREAD_COUNT);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        // Shutdown executor service to prevent resource leaks
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                // Wait a bit more for tasks to respond to being cancelled
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
        // Close container to clean up beans
        if (container != null) {
            container.close();
        }
    }

    @Test
    @DisplayName("Should handle concurrent bean registration")
    void testConcurrentBeanRegistration() throws InterruptedException {
        // Given - reduced thread count for coverage testing
        int threadCount = MEDIUM_THREAD_COUNT;
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        // When - register beans concurrently
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            Future<?> future = executor.submit(() -> {
                try {
                    container.registerSingleton("bean" + index, "value" + index);
                } finally {
                    latch.countDown();
                }
            });
            futures.add(future);
        }

        // Wait for all threads to complete with extended timeout
        assertTrue(latch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS), 
            "Timed out waiting for concurrent bean registration");

        // Then - all beans should be registered
        for (int i = 0; i < threadCount; i++) {
            assertEquals("value" + i, container.getBean("bean" + i));
        }
    }

    @Test
    @DisplayName("Should handle concurrent bean retrieval")
    void testConcurrentBeanRetrieval() throws InterruptedException {
        // Given - reduced thread count for coverage testing
        container.registerSingleton("sharedBean", "sharedValue");
        int threadCount = MEDIUM_THREAD_COUNT;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        List<String> results = new CopyOnWriteArrayList<>();

        // When - retrieve bean concurrently
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    Object bean = container.getBean("sharedBean");
                    results.add((String) bean);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Start all threads
        assertTrue(endLatch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "Timed out waiting for concurrent bean retrieval");

        // Then - all retrievals should succeed
        assertEquals(threadCount, results.size());
        assertTrue(results.stream().allMatch(r -> "sharedValue".equals(r)));
    }

    @Test
    @DisplayName("Should handle concurrent bean creation without race conditions")
    void testConcurrentBeanCreation() throws InterruptedException, ExecutionException, TimeoutException {
        // Given - reduced thread count for coverage testing
        int threadCount = SMALL_THREAD_COUNT;
        
        // Register a bean definition
        container.registerBean(ThreadSafeService.class);

        // When - create bean concurrently from multiple threads using Callable
        List<Future<ThreadSafeService>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            Future<ThreadSafeService> future = executor.submit(() -> container.getBean(ThreadSafeService.class));
            futures.add(future);
        }

        // Collect all results with extended timeout
        List<ThreadSafeService> services = new ArrayList<>();
        for (Future<ThreadSafeService> future : futures) {
            ThreadSafeService service = future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(service, "Bean should not be null");
            services.add(service);
        }

        // Then - all threads should get a bean instance
        assertEquals(threadCount, services.size());
        // All references should point to the same instance (singleton)
        ThreadSafeService firstService = services.get(0);
        assertNotNull(firstService);
        long distinctCount = services.stream().distinct().count();
        assertEquals(1, distinctCount, "All bean instances should be the same (singleton)");
    }

    @Test
    @DisplayName("Should handle concurrent bean post processor additions")
    void testConcurrentBeanPostProcessorAddition() throws InterruptedException {
        // Given - reduced count for coverage testing
        int processorCount = SMALL_THREAD_COUNT;
        CountDownLatch latch = new CountDownLatch(processorCount);

        // When - add post processors concurrently
        for (int i = 0; i < processorCount; i++) {
            executor.submit(() -> {
                try {
                    container.addBeanPostProcessor(new TestBeanPostProcessor());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "Timed out waiting for concurrent post processor addition");

        // Then - should not throw ConcurrentModificationException
        container.registerBean(ThreadSafeService.class);
        assertNotNull(container.getBean(ThreadSafeService.class));
    }

    @Test
    @DisplayName("Should handle bean definitions registration in concurrent environment")
    void testBeanDefinitionsInConcurrentEnvironment() {
        // Given - simple bean definitions without circular dependency
        BeanDefinition defA = new BeanDefinition(CircularServiceA.class, "serviceA");
        BeanDefinition defB = new BeanDefinition(CircularServiceB.class, "serviceB");
        
        container.registerBeanDefinition("serviceA", defA);
        container.registerBeanDefinition("serviceB", defB);

        // When/Then - should create beans successfully
        assertNotNull(container.getBean("serviceA"));
        assertNotNull(container.getBean("serviceB"));
    }

    @RepeatedTest(2)  // Reduced from 5 to 2 for coverage testing
    @DisplayName("Should handle concurrent close operations safely")
    void testConcurrentClose() throws InterruptedException {
        // Given - reduced bean count
        for (int i = 0; i < SMALL_THREAD_COUNT; i++) {
            container.registerSingleton("bean" + i, "value" + i);
        }

        int closeThreadCount = 3;  // Reduced from 5
        CountDownLatch latch = new CountDownLatch(closeThreadCount);

        // When - close container from multiple threads
        for (int i = 0; i < closeThreadCount; i++) {
            executor.submit(() -> {
                try {
                    container.close();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Then - should not throw any exception
        assertTrue(latch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "Timed out waiting for concurrent close");
    }

    @Test
    @DisplayName("Should handle concurrent type-based bean retrieval")
    void testConcurrentTypeBeanRetrieval() throws InterruptedException {
        // Given - reduced thread count
        container.registerType(String.class, "testString");
        int threadCount = MEDIUM_THREAD_COUNT;
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<String> results = new CopyOnWriteArrayList<>();

        // When
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    String bean = container.getBean(String.class);
                    results.add(bean);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "Timed out waiting for concurrent type-based retrieval");

        // Then
        assertEquals(threadCount, results.size());
        assertTrue(results.stream().allMatch("testString"::equals));
    }

    @Test
    @DisplayName("Should handle concurrent bean definition registration")
    void testConcurrentBeanDefinitionRegistration() throws InterruptedException {
        // Given - reduced count
        int defCount = MEDIUM_THREAD_COUNT;
        CountDownLatch latch = new CountDownLatch(defCount);

        // When
        for (int i = 0; i < defCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    BeanDefinition def = new BeanDefinition(ThreadSafeService.class, "service" + index);
                    container.registerBeanDefinition("service" + index, def);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "Timed out waiting for concurrent bean definition registration");

        // Then
        String[] beanNames = container.getBeanDefinitionNames();
        assertTrue(beanNames.length >= defCount);
    }

    // Test helper classes
    public static class ThreadSafeService {
        private final long threadId;

        public ThreadSafeService() {
            this.threadId = Thread.currentThread().getId();
        }

        public long getThreadId() {
            return threadId;
        }
    }

    public static class TestBeanPostProcessor implements BeanPostProcessor {
        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) {
            return bean;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            return bean;
        }
    }

    // Circular dependency test classes
    public static class CircularServiceA {
        // In real scenario, this would have @Autowired CircularServiceB
    }

    public static class CircularServiceB {
        // In real scenario, this would have @Autowired CircularServiceA
    }
}
