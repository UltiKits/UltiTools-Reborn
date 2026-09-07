package com.ultikits.ultitools.uat;

import com.ultikits.ultitools.uat.fixtures.AlphaCommands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@link SurfaceAssembler}'s two fail-loud edge behaviours (Phase 10 plan 10-02, Task 2):
 * two distinct classes sharing a simple name never merge into one row, and two concurrent writers
 * of the same {@code surface.json} path never leave a corrupted or truncated file.
 */
@DisplayName("SurfaceAssembler edge behaviours")
class SurfaceAssemblerEdgeTest {

    @Test
    @DisplayName("two classes sharing a simple name and method never merge into one row -- they raise, naming both fully qualified classes")
    void sameSimpleNameClassesNeverMergeIntoOneRow() {
        List<Class<?>> classes = Arrays.asList(
                AlphaCommands.class,
                com.ultikits.ultitools.uat.fixtures.beta.AlphaCommands.class);

        assertThatThrownBy(() -> new SurfaceAssembler().assemble("Fixture", classes))
                .isInstanceOf(ExtractorException.class)
                .hasMessageContaining(AlphaCommands.class.getName())
                .hasMessageContaining(com.ultikits.ultitools.uat.fixtures.beta.AlphaCommands.class.getName());
    }

    @Test
    @DisplayName("two concurrent writers of the same output path leave a file byte-identical to a single-run file")
    @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes")
    // The RuntimeException wrapper below is the standard idiom for propagating a checked
    // exception out of a Runnable submitted to an ExecutorService, so future.get() below
    // surfaces it wrapped in an ExecutionException rather than swallowing it silently.
    void concurrentWritesNeverCorruptTheOutputFile(@TempDir Path scratchRoot) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", "COM-deadbeef");
        row.put("kind", "command");
        items.add(row);

        String expected = CanonicalJsonWriter.toJsonString(1, items, Collections.emptyMap());

        Path output = scratchRoot.resolve("surface.json");
        int writerCount = 2;
        ExecutorService pool = Executors.newFixedThreadPool(writerCount);
        CountDownLatch startingGate = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < writerCount; i++) {
            futures.add(pool.submit(() -> {
                try {
                    startingGate.await();
                    CanonicalJsonWriter.write(output, 1, items, Collections.emptyMap());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        startingGate.countDown();
        for (java.util.concurrent.Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        String actual = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        assertThat(actual).isEqualTo(expected);
    }
}
