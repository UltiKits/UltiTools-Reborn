package com.ultikits.ultitools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ultikits.ultitools.manager.ModuleJarAdmission;

class EmbeddedDatapacksTest {
    @TempDir Path data;
    private final Logger logger = Logger.getAnonymousLogger();
    private final ModuleJarAdmission admission = new ModuleJarAdmission();
    private final ContributionRegistry registry = new ContributionRegistry(admission, logger);

    @Test
    void copiesOnlyBundledEntriesInOrderAndDoesNotRewriteUnchangedZip() throws Exception {
        Path jar = BootstrapFixtures.jar(data.resolve("demo.jar"), BootstrapFixtures.descriptor("Demo", null), Map.of(),
                Map.of("datapacks/one/pack.mcmeta", "{\"pack\":{}}", "datapacks/one/data/demo/recipe/a.json", "{\"demo\":1}",
                        "datapacks/other/pack.mcmeta", "{}"));
        var module = new ModuleContributions("Demo", jar);
        module.datapacks().embedded("one");
        module.seal();
        registry.accept(module);
        EmbeddedDatapacks.prepare(data, registry, logger);
        Path target = data.resolve("bootstrap/datapacks/demo.one.zip");
        try (var zip = new ZipFile(target.toFile()); var original = new ZipFile(jar.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName).toList())
                    .isEqualTo(List.of("data/demo/recipe/a.json", "pack.mcmeta"));
            for (var entry : zip.stream().toList()) {
                assertThat(entry.getTime()).isZero();
                try (var actual = zip.getInputStream(entry);
                        var expected = original.getInputStream(original.getEntry("datapacks/one/" + entry.getName()))) {
                    assertThat(actual.readAllBytes()).isEqualTo(expected.readAllBytes());
                }
            }
        }
        Files.setLastModifiedTime(target, FileTime.fromMillis(100_000L));
        EmbeddedDatapacks.prepare(data, registry, logger);
        assertThat(Files.getLastModifiedTime(target)).isEqualTo(FileTime.fromMillis(100_000L));
    }

    @Test
    void prunesFrameworkOwnedZipsButNeverOperatorDataPacks() throws Exception {
        Path owned = Files.createDirectories(data.resolve("bootstrap/datapacks"));
        Path old = Files.writeString(owned.resolve("removed.old.zip"), "derived from a removed JAR");
        Path operator = Files.writeString(Files.createDirectories(data.resolve("world/datapacks"))
                .resolve("removed.old.zip"), "operator's own data pack");
        Path note = Files.writeString(owned.resolve("notes.txt"), "not a derived ZIP");
        EmbeddedDatapacks.prepare(data, registry, logger);
        assertThat(old).doesNotExist();
        assertThat(operator).hasContent("operator's own data pack");
        assertThat(note).hasContent("not a derived ZIP");
    }

    @Test
    void changedArchiveWithoutMetadataFailsOnlyTheDeclaringModule() throws Exception {
        Path jar = data.resolve("demo.jar");
        var descriptor = BootstrapFixtures.descriptor("Demo", null);
        BootstrapFixtures.jar(jar, descriptor, Map.of(), Map.of("datapacks/one/pack.mcmeta", "{}"));
        var module = new ModuleContributions("Demo", jar);
        module.datapacks().embedded("one");
        module.seal();
        registry.accept(module);
        BootstrapFixtures.jar(jar, descriptor, Map.of());
        EmbeddedDatapacks.prepare(data, registry, logger);
        assertThat(registry.failed(jar)).isTrue();
        assertThat(data.resolve("bootstrap/datapacks/demo.one.zip")).doesNotExist();
    }

    @Test
    void rechecksTheArchiveForWorldGenerationBeforeExtracting() throws Exception {
        Path jar = data.resolve("demo.jar");
        var descriptor = BootstrapFixtures.descriptor("Demo", null);
        BootstrapFixtures.jar(jar, descriptor, Map.of(), Map.of("datapacks/one/pack.mcmeta", "{}"));
        var module = new ModuleContributions("Demo", jar);
        module.datapacks().embedded("one");
        module.seal();
        registry.accept(module);

        BootstrapFixtures.jar(jar, descriptor, Map.of(), Map.of("datapacks/one/pack.mcmeta", "{}",
                "datapacks/one/overlay/data/demo/worldgen/biome/probe.json", "{}"));
        EmbeddedDatapacks.prepare(data, registry, logger);

        assertThat(registry.failed(jar)).isTrue();
        assertThat(registry.worldgenRefusals()).containsExactly(new ModuleContributions.WorldgenRefusal(
                "Demo", "one", "overlay/data/demo/worldgen/"));
        assertThat(registry.hasDatapacks()).isFalse();
        assertThat(data.resolve("bootstrap/datapacks/demo.one.zip")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"data/demo/../a.json", "data/./demo/a.json", "data\\demo\\a.json",
            "data//demo/a.json", "/data/demo/a.json"})
    void rejectsMalformedRelativeEntryPaths(String relative) throws Exception {
        Path jar = BootstrapFixtures.jar(data.resolve("demo.jar"), BootstrapFixtures.descriptor("Demo", null), Map.of(),
                Map.of("datapacks/one/pack.mcmeta", "{}", "datapacks/one/" + relative, "{}"));
        var module = new ModuleContributions("Demo", jar);
        module.datapacks().embedded("one");
        module.seal();
        registry.accept(module);

        EmbeddedDatapacks.prepare(data, registry, logger);

        assertThat(admission.inspect(jar.toFile()).getBootstrapFailure())
                .hasMessage("Invalid bundled data pack entry: " + relative);
        assertThat(registry.hasDatapacks()).isFalse();
        assertThat(data.resolve("bootstrap/datapacks/demo.one.zip")).doesNotExist();
    }

    @Test
    void failureOfTheSecondPackPrunesTheFirstDerivedZipToo() throws Exception {
        Path jar = data.resolve("demo.jar");
        var descriptor = BootstrapFixtures.descriptor("Demo", null);
        BootstrapFixtures.jar(jar, descriptor, Map.of(),
                Map.of("datapacks/one/pack.mcmeta", "{}", "datapacks/two/pack.mcmeta", "{}"));
        var module = new ModuleContributions("Demo", jar);
        module.datapacks().embedded("one");
        module.datapacks().embedded("two");
        module.seal();
        registry.accept(module);
        EmbeddedDatapacks.prepare(data, registry, logger);
        Path first = data.resolve("bootstrap/datapacks/demo.one.zip");
        Path second = data.resolve("bootstrap/datapacks/demo.two.zip");
        assertThat(first).isRegularFile();
        assertThat(second).isRegularFile();

        BootstrapFixtures.jar(jar, descriptor, Map.of(), Map.of("datapacks/one/pack.mcmeta", "{}"));
        EmbeddedDatapacks.prepare(data, registry, logger);

        assertThat(registry.failed(jar)).isTrue();
        assertThat(admission.inspect(jar.toFile()).getBootstrapFailure()).hasMessageContaining("datapacks/two/pack.mcmeta");
        assertThat(registry.hasDatapacks()).isFalse();
        assertThat(first).doesNotExist();
        assertThat(second).doesNotExist();
    }
}
