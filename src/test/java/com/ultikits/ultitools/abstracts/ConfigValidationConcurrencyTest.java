package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.NotEmpty;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.exceptions.ConfigurationException;

/**
 * UAT-01 backstop: two {@code @ConfigEntity} classes belonging to different {@link
 * UltiToolsPlugin} instances validate concurrently without observing each other's in-flight
 * state.
 * <p>
 * {@code validateFields()} builds its {@code configFields} and {@code violations} lists as method
 * locals, and {@code ReflectionUtil.getAllFields} allocates a fresh list from
 * {@code Class.getDeclaredFields()} (itself a defensive copy) on every call -- so there is no
 * shared mutable state to contend for. Code inspection says so; this class is the directly
 * observed evidence that says so, which is what a "backstop" truth requires.
 * <br>
 * UAT-01 兜底验证：分属不同 {@link UltiToolsPlugin} 实例的两个 {@code @ConfigEntity} 类并发校验时，
 * 互相看不到对方的中间状态。代码检视认为成立（局部变量、无共享缓存），本类提供直接观测证据。
 */
@DisplayName("Config validation concurrency isolation (UAT-01)")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ConfigValidationConcurrencyTest {

    private static final int ROUNDS = 40;

    @TempDir
    Path tempDir;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    /**
     * Builds a plugin mock whose config folder is a subdirectory unique to {@code moduleName}, so
     * the two threads never share a file either.
     */
    private UltiToolsPlugin newPluginMock(String moduleName) throws IOException {
        Path folder = tempDir.resolve(moduleName);
        Files.createDirectories(folder);
        UltiToolsPlugin plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn(moduleName);
        lenient().when(plugin.getConfigFolder()).thenReturn(folder.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(folder.toFile(), invocation.getArgument(0)));
        return plugin;
    }

    private void writeYaml(String moduleName, String fileName, String body) throws IOException {
        Path folder = tempDir.resolve(moduleName);
        Files.createDirectories(folder);
        Files.write(folder.resolve(fileName), body.getBytes(StandardCharsets.UTF_8));
    }

    @RepeatedTest(ROUNDS)
    @DisplayName("two violating configs on two threads produce two uncontaminated refusals")
    // See the note in PluginManagerContainerIsolationTest: PMD checks a @RepeatedTest method
    // against methodPattern, not junit5TestPattern, because only @Test is recognised as a test.
    @SuppressWarnings("PMD.MethodNamingConventions")
    void concurrentValidation_refusalsNeverCrossContaminate() throws Exception {
        UltiToolsPlugin alphaPlugin = newPluginMock("AlphaModule");
        UltiToolsPlugin betaPlugin = newPluginMock("BetaModule");
        writeYaml("AlphaModule", "alpha.yml", "alphaInterval: 999");
        writeYaml("BetaModule", "beta.yml", "betaTitle: ''");

        CountDownLatch startGate = new CountDownLatch(1);

        Callable<ConfigurationException> alphaTask = () -> {
            startGate.await();
            try {
                new AlphaConfig("alpha.yml").init(alphaPlugin);
                return null;
            } catch (ConfigurationException e) {
                return e;
            }
        };
        Callable<ConfigurationException> betaTask = () -> {
            startGate.await();
            try {
                new BetaConfig("beta.yml").init(betaPlugin);
                return null;
            } catch (ConfigurationException e) {
                return e;
            }
        };

        Future<ConfigurationException> alphaFuture = executor.submit(alphaTask);
        Future<ConfigurationException> betaFuture = executor.submit(betaTask);
        startGate.countDown();

        ConfigurationException alphaFailure = alphaFuture.get(20, TimeUnit.SECONDS);
        ConfigurationException betaFailure = betaFuture.get(20, TimeUnit.SECONDS);

        assertThat(alphaFailure)
                .as("AlphaModule's out-of-range value must refuse AlphaModule")
                .isNotNull();
        assertThat(betaFailure)
                .as("BetaModule's empty value must refuse BetaModule")
                .isNotNull();

        assertThat(alphaFailure.getMessage())
                .as("alpha's refusal names only alpha's own field -- a betaTitle here would mean "
                        + "one thread's violation list observed the other's in-flight state")
                .contains("alphaInterval")
                .doesNotContain("betaTitle")
                .contains("AlphaModule")
                .doesNotContain("BetaModule");

        assertThat(betaFailure.getMessage())
                .as("beta's refusal names only beta's own field")
                .contains("betaTitle")
                .doesNotContain("alphaInterval")
                .contains("BetaModule")
                .doesNotContain("AlphaModule");
    }

    @Test
    @DisplayName("both threads succeeding is equally uncontaminated (valid values)")
    void concurrentValidation_bothSucceedIndependently() throws Exception {
        UltiToolsPlugin alphaPlugin = newPluginMock("AlphaOk");
        UltiToolsPlugin betaPlugin = newPluginMock("BetaOk");

        List<Future<Throwable>> futures = new ArrayList<>();
        CountDownLatch startGate = new CountDownLatch(1);

        // Written once: the content is identical every round, and the concurrency under test is
        // the two per-thread init() loops below, not this setup.
        writeYaml("AlphaOk", "alpha.yml", "alphaInterval: 5");
        writeYaml("BetaOk", "beta.yml", "betaTitle: 'ok'");

        futures.add(executor.submit(() -> {
            startGate.await();
            try {
                for (int i = 0; i < ROUNDS; i++) {
                    new AlphaConfig("alpha.yml").init(alphaPlugin);
                }
                return null;
            } catch (Throwable t) {
                return t;
            }
        }));
        futures.add(executor.submit(() -> {
            startGate.await();
            try {
                for (int i = 0; i < ROUNDS; i++) {
                    new BetaConfig("beta.yml").init(betaPlugin);
                }
                return null;
            } catch (Throwable t) {
                return t;
            }
        }));
        startGate.countDown();

        for (Future<Throwable> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS))
                    .as("a valid value on one thread must never be refused because of the other "
                            + "thread's concurrent validation")
                    .isNull();
        }
    }

    /** AlphaModule's config -- field names deliberately share no substring with {@link BetaConfig}. */
    private static final class AlphaConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "alphaInterval", comment = "Alpha interval")
        @Range(min = 1, max = 10)
        private int alphaInterval = 5;

        AlphaConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** BetaModule's config. */
    private static final class BetaConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "betaTitle", comment = "Beta title")
        @NotEmpty
        private String betaTitle = "default";

        BetaConfig(String configFilePath) {
            super(configFilePath);
        }
    }
}
