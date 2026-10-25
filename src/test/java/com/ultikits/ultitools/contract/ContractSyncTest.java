package com.ultikits.ultitools.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractSyncTest {
    private static final Path ROOT = Paths.get("src/test/resources", ContractFixtures.ROOT);
    private static final Path LOCK = ROOT.getParent().resolve("contract.lock.json");

    @Test
    void frozenTreeMatchesLockAndJavaVersion() throws Exception {
        verify(ROOT, lock(), javaVersion());
        assertThat(ContractFixtures.legacyUuidCases()).hasSize(26);
    }

    @Test
    void rejectsChangedMissingAndExtraFiles(@TempDir Path temp) throws Exception {
        copyTree(temp);
        Path file = temp.resolve("fixtures/agent/command.execute/valid/say.json");
        byte[] original = Files.readAllBytes(file);
        byte[] changed = original.clone();
        changed[0] ^= 1;
        Files.write(file, changed);
        assertThatThrownBy(() -> verify(temp, lock(), javaVersion())).isInstanceOf(AssertionError.class)
                .hasMessageContaining("say.json");
        Files.delete(file);
        assertThatThrownBy(() -> verify(temp, lock(), javaVersion())).isInstanceOf(AssertionError.class)
                .hasMessageContaining("say.json");
        Files.write(file, original);
        Files.write(temp.resolve("extra.json"), new byte[] {123, 125});
        assertThatThrownBy(() -> verify(temp, lock(), javaVersion())).isInstanceOf(AssertionError.class)
                .hasMessageContaining("extra.json");
    }

    @Test
    void rejectsChangedManifestLockAndVersion(@TempDir Path temp) throws Exception {
        copyTree(temp);
        JsonObject lock = lock();
        lock.addProperty("manifestSha256", "wrong");
        assertThatThrownBy(() -> verify(temp, lock, javaVersion())).isInstanceOf(AssertionError.class)
                .hasMessageContaining("MANIFEST");
        assertThatThrownBy(() -> verify(temp, lock(), "0.0.0")).isInstanceOf(AssertionError.class)
                .hasMessageContaining("contractVersion");
        Files.write(temp.resolve("MANIFEST.json"), "{}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> verify(temp, lock(), javaVersion())).isInstanceOf(AssertionError.class)
                .hasMessageContaining("MANIFEST");
    }

    static void verify(Path root, JsonObject lock, String version) throws Exception {
        assertThat(lock.get("contractVersion").getAsString()).as("contractVersion").isEqualTo(version);
        assertThat(lock.get("frozen").getAsBoolean()).isTrue();
        assertThat(sha256(root.resolve("MANIFEST.json"))).as("MANIFEST SHA-256")
                .isEqualTo(lock.get("manifestSha256").getAsString());
        JsonObject manifest = JsonParser.parseString(new String(Files.readAllBytes(root.resolve("MANIFEST.json")),
                StandardCharsets.UTF_8)).getAsJsonObject();
        assertThat(manifest.get("contractVersion")).isEqualTo(lock.get("contractVersion"));
        JsonObject files = manifest.getAsJsonObject("files");
        Set<String> expected = new HashSet<>(files.keySet());
        expected.add("MANIFEST.json");
        try (Stream<Path> stream = Files.walk(root)) {
            Set<String> actual = stream.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/')).collect(Collectors.toSet());
            assertThat(actual).as("Contract files").containsExactlyInAnyOrderElementsOf(expected);
        }
        for (Map.Entry<String, JsonElement> entry : files.entrySet()) {
            assertThat(sha256(root.resolve(entry.getKey()))).as(entry.getKey()).isEqualTo(entry.getValue().getAsString());
        }
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static String javaVersion() throws Exception {
        Field field = Class.forName("com.ultikits.ultitools.utils.AgentMessageTypes").getDeclaredField("CONTRACT_VERSION");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private static JsonObject lock() throws IOException {
        return JsonParser.parseString(new String(Files.readAllBytes(LOCK), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String sha256(Path path) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        StringBuilder hex = new StringBuilder();
        for (byte value : hash) {
            hex.append(Character.forDigit((value & 255) >>> 4, 16)).append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }

    private static void copyTree(Path destination) throws IOException {
        try (Stream<Path> stream = Files.walk(ROOT)) {
            for (Path path : stream.collect(Collectors.toList())) {
                Path target = destination.resolve(ROOT.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
    }
}
