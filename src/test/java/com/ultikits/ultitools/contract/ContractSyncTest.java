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
    private static final Path CATALOGUE_ROOT = Paths.get("src/test/resources", ContractFixtures.CATALOGUE_ROOT);
    private static final Path CATALOGUE_LOCK = CATALOGUE_ROOT.getParent().resolve("contract.lock.json");
    /** The frozen catalogue release's MANIFEST hash, as the backend's RELEASES.json records it (commit 682735c9). */
    private static final String CATALOGUE_MANIFEST_SHA256 =
            "673feab2e47d38d79bfa3e57e3bf525749389d6cbebb49944e1b15cb31f9abd2";

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

    @Test
    void frozenCatalogueTreeMatchesItsLockAndJavaVersion() throws Exception {
        JsonObject lock = lock(CATALOGUE_LOCK);
        verify(CATALOGUE_ROOT, lock, catalogueVersion());
        assertThat(lock.get("manifestSha256").getAsString()).as("pinned frozen MANIFEST hash")
                .isEqualTo(CATALOGUE_MANIFEST_SHA256);
        assertThat(lock.get("sourceCommit").getAsString()).isEqualTo("682735c9a240bce44e90a3622d23b4adda9e9c2c");
        assertThat(lock.get("sourceRef").getAsString()).isEqualTo("main");
        assertThat(ContractFixtures.cataloguePaths()).as("schemas").filteredOn(p -> p.startsWith("schemas/"))
                .hasSize(21);
        assertThat(ContractFixtures.cataloguePaths()).as("fixtures").filteredOn(p -> p.startsWith("fixtures/"))
                .hasSize(57);
    }

    @Test
    void catalogueCopyRejectsAFlippedByteAMissingFileAndAnExtraFile(@TempDir Path temp) throws Exception {
        copyTree(CATALOGUE_ROOT, temp);
        Path file = temp.resolve("fixtures/versionGet/response/valid/public-download.json");
        byte[] original = Files.readAllBytes(file);
        byte[] changed = original.clone();
        changed[original.length / 2] ^= 1;
        Files.write(file, changed);
        assertThatThrownBy(() -> verify(temp, lock(CATALOGUE_LOCK), catalogueVersion()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("public-download.json");
        Files.delete(file);
        assertThatThrownBy(() -> verify(temp, lock(CATALOGUE_LOCK), catalogueVersion()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("public-download.json");
        Files.write(file, original);
        verify(temp, lock(CATALOGUE_LOCK), catalogueVersion());
        Files.write(temp.resolve("fixtures/extra.json"), new byte[] {123, 125});
        assertThatThrownBy(() -> verify(temp, lock(CATALOGUE_LOCK), catalogueVersion()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("extra.json");
    }

    @Test
    void catalogueCopyRejectsAChangedManifestLockOrVersion(@TempDir Path temp) throws Exception {
        copyTree(CATALOGUE_ROOT, temp);
        JsonObject lock = lock(CATALOGUE_LOCK);
        lock.addProperty("manifestSha256", "wrong");
        assertThatThrownBy(() -> verify(temp, lock, catalogueVersion())).isInstanceOf(AssertionError.class)
                .hasMessageContaining("MANIFEST");
        assertThatThrownBy(() -> verify(temp, lock(CATALOGUE_LOCK), "0.0.0")).isInstanceOf(AssertionError.class)
                .hasMessageContaining("contractVersion");
        Files.write(temp.resolve("MANIFEST.json"), "{}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> verify(temp, lock(CATALOGUE_LOCK), catalogueVersion()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("MANIFEST");
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

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static String catalogueVersion() throws Exception {
        Field field = Class.forName("com.ultikits.ultitools.utils.CatalogueClient").getDeclaredField("CONTRACT_VERSION");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private static JsonObject lock() throws IOException {
        return lock(LOCK);
    }

    private static JsonObject lock(Path path) throws IOException {
        return JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
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
        copyTree(ROOT, destination);
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.collect(Collectors.toList())) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
    }
}
