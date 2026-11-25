package com.ultikits.ultitools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;

class ContributionValidationTest {
    @TempDir Path folder;

    @Test
    void enchantmentsMustBeNewKeysInTheModuleNamespace() {
        var module = new ModuleContributions("My Module", folder.resolve("module.jar"));
        Key foreign = Key.key("minecraft:sharpness");
        assertThatThrownBy(() -> module.enchantments().register(foreign, (builder, tags) -> {}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minecraft:sharpness", "my_module");
        Key own = Key.key("my_module:edge");
        module.enchantments().register(own, (builder, tags) -> {});
        assertThatThrownBy(() -> module.enchantments().register(own, (builder, tags) -> {}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("my_module:edge");
    }

    @Test
    void tagReplacementsRequireOwnNamespaceButAdditionsDoNotAndAreCopied() {
        var module = new ModuleContributions("Demo", folder.resolve("module.jar"));
        Key planks = Key.key("minecraft:planks");
        Key sword = Key.key("minecraft:diamond_sword");
        var values = new ArrayList<>(List.of(sword));
        assertThatThrownBy(() -> module.tags().setTag(RegistryKey.ITEM, planks, values))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minecraft:planks", "demo");
        module.tags().addToTag(RegistryKey.ITEM, planks, values);
        values.clear();
        module.seal();
        assertThat(module.tagChanges()).singleElement().satisfies(change -> {
            assertThat(change.values()).containsExactly(sword);
            assertThat(change.replace()).isFalse();
        });
    }

    @Test
    void unsupportedTagRegistriesFailAtDeclaration() {
        var tags = new ModuleContributions("Demo", folder.resolve("module.jar")).tags();
        Key key = Key.key("demo:test");
        assertThatThrownBy(() -> tags.addToTag(RegistryKey.PAINTING_VARIANT, key, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minecraft:painting_variant");
        assertThatThrownBy(() -> tags.setTag(RegistryKey.BLOCK, key, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minecraft:block");
    }

    @Test
    void packNameAndMetadataAreValidatedAgainstTheDeclaringJar() throws Exception {
        Path jar = BootstrapFixtures.jar(folder.resolve("demo.jar"), BootstrapFixtures.descriptor("Demo", null), Map.of(),
                Map.of("datapacks/wrong/pack.mcmeta", "[]", "datapacks/malformed/pack.mcmeta", "{not json",
                        "datapacks/valid/pack.mcmeta", "{}"));
        var packs = new ModuleContributions("Demo", jar).datapacks();
        assertThatThrownBy(() -> packs.embedded("../other")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("../other");
        assertThatThrownBy(() -> packs.embedded("missing")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("datapacks/missing/pack.mcmeta");
        assertThatThrownBy(() -> packs.embedded("wrong")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wrong", "JSON object");
        assertThatThrownBy(() -> packs.embedded("malformed")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed", "JSON object");
        packs.embedded("valid");
        assertThatThrownBy(() -> packs.embedded("valid")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid", "already declared");
    }
}
