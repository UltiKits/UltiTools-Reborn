package com.ultikits.ultitools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.manager.ModuleJarAdmission;

import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;

class ContributionRegistryTagsTest {
    @TempDir Path folder;
    private final Logger logger = Logger.getAnonymousLogger();
    private final ModuleJarAdmission admission = new ModuleJarAdmission();
    private final ContributionRegistry registry = new ContributionRegistry(admission, logger);

    @Test
    void reloadReplaysDataWithoutModuleCode() throws Exception {
        ModuleContributions module = module("Demo");
        var definitions = new AtomicInteger();
        Key sword = Key.key("minecraft:diamond_sword");
        Key tag = Key.key("demo:tools");
        var values = new ArrayList<>(List.of(sword));
        module.enchantments().register(Key.key("demo:edge"), (builder, tags) -> definitions.incrementAndGet());
        module.tags().setTag(RegistryKey.ITEM, tag, values);
        values.clear();
        accept(module);
        registry.composeEnchantments((key, definition) -> definition.accept(null), mock(ComposeTags.class));
        List<ModuleContributions.TagChange> writes = new ArrayList<>();
        registry.applyTags(RegistryKey.ITEM, writes::add);
        registry.beginEnable();
        registry.applyTags(RegistryKey.ITEM, writes::add);
        var change = new ModuleContributions.TagChange(RegistryKey.ITEM, tag, List.of(sword), true);
        assertThat(writes).containsExactly(change, change);
        assertThat(definitions).hasValue(1);
    }

    @Test
    void failedModuleIsSkippedOnLaterEvents() throws Exception {
        ModuleContributions failed = module("Bad");
        failed.enchantments().register(Key.key("bad:edge"), (builder, tags) -> { throw new AssertionError("bad"); });
        failed.tags().setTag(RegistryKey.ITEM, Key.key("bad:tools"), List.of());
        failed.datapacks().embedded("test");
        accept(failed);
        ModuleContributions ok = module("Ok");
        ok.tags().addToTag(RegistryKey.ITEM, Key.key("minecraft:planks"), List.of(Key.key("minecraft:stick")));
        ok.datapacks().embedded("test");
        accept(ok);
        EmbeddedDatapacks.prepare(folder, registry, logger);
        registry.composeEnchantments((key, definition) -> definition.accept(null), mock(ComposeTags.class));
        List<Key> tags = new ArrayList<>();
        registry.applyTags(RegistryKey.ITEM, change -> tags.add(change.tag()));
        List<String> packs = new ArrayList<>();
        registry.discoverDatapacks((path, id) -> packs.add(id));
        assertThat(tags).containsExactly(Key.key("minecraft:planks"));
        assertThat(packs).containsExactly("ok.test");
    }

    @Test
    void packDiscoveryRepeatsAndOnlyStartupFailureRefusesTheModule() throws Exception {
        ModuleContributions bad = module("Bad");
        bad.datapacks().embedded("test");
        accept(bad);
        ModuleContributions ok = module("Ok");
        ok.datapacks().embedded("test");
        accept(ok);
        EmbeddedDatapacks.prepare(folder, registry, logger);
        List<Path> writes = new ArrayList<>();
        PackSink sink = (path, id) -> !"bad.test".equals(id) && writes.add(path);
        registry.discoverDatapacks(sink);
        registry.discoverDatapacks(sink);
        registry.beginEnable();
        registry.discoverDatapacks((path, id) -> false);
        assertThat(registry.failed(bad.jar())).isTrue();
        assertThat(registry.failed(ok.jar())).isFalse();
        assertThat(writes).containsExactly(folder.resolve("bootstrap/datapacks/ok.test.zip"),
                folder.resolve("bootstrap/datapacks/ok.test.zip"));
    }

    private ModuleContributions module(String name) throws Exception {
        var jar = BootstrapFixtures.jar(folder.resolve(name + ".jar"), BootstrapFixtures.descriptor(name, null), Map.of(),
                Map.of("datapacks/test/pack.mcmeta", "{}"));
        admission.inspect(jar.toFile());
        return new ModuleContributions(name, jar);
    }

    private void accept(ModuleContributions module) {
        module.seal();
        registry.accept(module);
    }
}
