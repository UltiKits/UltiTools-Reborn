package com.ultikits.ultitools.buildtools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Replays the workflow's actual seed step, rather than only looking for strings in its YAML. */
class RandomOrderWorkflowTest {
    @TempDir Path directory;

    @Test
    void independentCheckCoversMainAndOffersManualReplay() throws Exception {
        Map<?, ?> workflow = workflow();
        // SnakeYAML's YAML 1.1 resolver reads GitHub's unquoted "on" key as true.
        Map<?, ?> events = (Map<?, ?>) workflow.get(Boolean.TRUE);
        for (String event : new String[] {"push", "pull_request"}) {
            assertThat(((Map<?, ?>) events.get(event)).get("branches")).isEqualTo(java.util.Collections.singletonList("main"));
        }
        Map<?, ?> inputs = (Map<?, ?>) ((Map<?, ?>) events.get("workflow_dispatch")).get("inputs");
        assertThat(inputs.get("seed")).isNotNull();
        Map<?, ?> job = job();
        assertThat(job.get("name").toString()).doesNotContain("Test & Coverage", "Release tooling regression suites");
        Map<?, ?> java = steps().stream().filter(step -> step.containsKey("uses")
                && step.get("uses").toString().startsWith("actions/setup-java@")).findFirst().get();
        assertThat(((Map<?, ?>) java.get("with")).get("distribution")).isEqualTo("temurin");
        assertThat(((Map<?, ?>) java.get("with")).get("java-version")).isEqualTo("21");
        Map<?, ?> tests = steps().stream().filter(step -> "Run unit tests in random order".equals(step.get("name")))
                .findFirst().get();
        assertThat(tests.get("run").toString()).contains("mvn -B test", "-Dsurefire.runOrder=random",
                "-Dsurefire.runOrder.random.seed=$SEED");
    }

    @Test
    void aFreshSeedIsPrintedAndRecordedInTheSummaryAndOutput() throws Exception {
        assertThat(runSeed("")).isZero();
        String output = read("outputs");
        assertThat(output).matches("seed=[0-9]+\\n");
        long seed = Long.parseLong(output.trim().substring("seed=".length()));
        assertThat(seed).isNotNegative();
        assertRecorded(Long.toString(seed));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "777", "9223372036854775807"})
    void manualSeedIsReplayedExactly(String seed) throws Exception {
        assertThat(runSeed(seed)).isZero();
        assertThat(read("outputs")).isEqualTo("seed=" + seed + "\n");
        assertRecorded(seed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "9223372036854775808", "777\ninjected=1", "$(touch injected)"})
    void invalidSeedsCannotInjectShellCommandsOrWorkflowOutputs(String seed) throws Exception {
        assertThat(runSeed(seed)).isNotZero();
        assertThat(directory.resolve("outputs")).doesNotExist();
        assertThat(directory.resolve("summary")).doesNotExist();
        assertThat(directory.resolve("injected")).doesNotExist();
    }

    private void assertRecorded(String seed) throws Exception {
        String replay = "mvn -B test -Dsurefire.runOrder=random -Dsurefire.runOrder.random.seed=" + seed;
        assertThat(read("log")).contains("Random-order seed: " + seed, replay);
        assertThat(read("summary")).contains("Seed: `" + seed + "`", replay);
    }

    private int runSeed(String seed) throws Exception {
        Map<?, ?> step = steps().stream().filter(value -> "seed".equals(value.get("id"))).findFirst().get();
        ProcessBuilder builder = new ProcessBuilder("bash", "-e", "-o", "pipefail", "-c", step.get("run").toString());
        builder.directory(directory.toFile());
        builder.environment().put("REPLAY_SEED", seed);
        builder.environment().put("GITHUB_OUTPUT", directory.resolve("outputs").toString());
        builder.environment().put("GITHUB_STEP_SUMMARY", directory.resolve("summary").toString());
        builder.redirectErrorStream(true).redirectOutput(directory.resolve("log").toFile());
        Process process = builder.start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).as("the offline seed step finishes").isTrue();
            return process.exitValue();
        } finally {
            process.destroyForcibly();
        }
    }

    private String read(String name) throws Exception {
        return new String(Files.readAllBytes(directory.resolve(name)), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> steps() throws Exception {
        return (List<Map<String, Object>>) job().get("steps");
    }

    private Map<?, ?> job() throws Exception {
        return (Map<?, ?>) ((Map<?, ?>) workflow().get("jobs")).get("random-order");
    }

    private Map<?, ?> workflow() throws Exception {
        try (InputStream input = Files.newInputStream(Paths.get(".github/workflows/random-order-tests.yml"))) {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
        }
    }
}
