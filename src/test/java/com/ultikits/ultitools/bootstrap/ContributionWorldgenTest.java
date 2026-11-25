package com.ultikits.ultitools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.ultikits.ultitools.ModuleClassLoader;
import com.ultikits.ultitools.manager.ModuleJarAdmission;

class ContributionWorldgenTest {
    private static final String PACK = "datapacks/land/";
    @TempDir Path folder;

    @ParameterizedTest
    @CsvSource({"data/foo/worldgen/biome/x.json, data/foo/worldgen/",
            "data/foo/dimension/x.json, data/foo/dimension/",
            "data/foo/dimension_type/x.json, data/foo/dimension_type/",
            "ov1/data/foo/worldgen/structure/x.json, ov1/data/foo/worldgen/"})
    void refusesWorldGenerationDirectoriesIncludingOverlays(String entry, String path) throws Exception {
        Path jar = BootstrapFixtures.jar(folder.resolve("Terrain.jar"), BootstrapFixtures.descriptor("Terrain", null),
                Map.of(), Map.of(PACK + "pack.mcmeta", "{}", PACK + entry, "{}"));
        var contributions = new ModuleContributions("Terrain", jar);
        assertThatThrownBy(() -> contributions.datapacks().embedded("land"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Data pack 'land' in module 'Terrain' "
                        + "contains world-generation data (" + path + "); UltiTools 7.0.0 does not load modules "
                        + "whose data packs add biomes, structures or dimensions.");
    }

    @Test
    void catchingARefusalDoesNotRestoreTheModuleOrItsOtherDeclarations() throws Exception {
        String hook = "fixture.Terrain";
        Path jar = BootstrapFixtures.jar(folder.resolve("Terrain.jar"), BootstrapFixtures.descriptor("Terrain", hook),
                Map.of(hook, BootstrapFixtures.caughtWorldgenHook(hook)),
                Map.of(PACK + "pack.mcmeta", "{}", PACK + "data/foo/worldgen/biome/x.json", "{}"));
        Logger logger = Logger.getAnonymousLogger();
        var admission = new ModuleJarAdmission();
        var entry = admission.inspect(jar.toFile());
        var registry = new ContributionRegistry(admission, logger);
        try (var loader = ModuleClassLoader.create(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            var result = ModuleBootstraps.runAll(admission, loader, logger, registry);
            assertThat(registry.modules()).isEmpty();
            assertThat(result.attempted).isEqualTo(1);
            assertThat(entry.getBootstrapFailure()).isSameAs(result.failures.get(jar))
                    .hasMessageContaining("land", "data/foo/worldgen/");
        }
    }

    @Test
    void recipesAdvancementsWorldgenTagsAndStructureTemplatesDoNotAddWorldgenRegistrations() throws Exception {
        Path jar = BootstrapFixtures.jar(folder.resolve("Content.jar"), BootstrapFixtures.descriptor("Content", null), Map.of(),
                Map.of(PACK + "pack.mcmeta", "{}", PACK + "data/foo/recipe/x.json", "{}",
                        PACK + "data/foo/advancement/x.json", "{}", PACK + "data/foo/tags/worldgen/biome/x.json", "{}",
                        PACK + "data/foo/structure/x.nbt", "fixture NBT contents"));
        var contributions = new ModuleContributions("Content", jar);
        contributions.datapacks().embedded("land");
        assertThat(contributions.packNames()).containsExactly("land");
    }
}
