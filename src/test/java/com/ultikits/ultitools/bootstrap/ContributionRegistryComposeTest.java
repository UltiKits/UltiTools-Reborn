package com.ultikits.ultitools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.manager.ModuleJarAdmission;

import io.papermc.paper.registry.data.EnchantmentRegistryEntry;
import net.kyori.adventure.key.Key;

class ContributionRegistryComposeTest {
    @TempDir Path folder;
    private final Logger logger = Logger.getAnonymousLogger();
    private final ModuleJarAdmission admission = new ModuleJarAdmission();
    private final ContributionRegistry registry = new ContributionRegistry(admission, logger);
    private final List<Key> written = new ArrayList<>();
    private final WritableRegistryView writer = (key, definition) -> {
        definition.accept(mock(EnchantmentRegistryEntry.Builder.class));
        written.add(key);
    };

    @Test
    void definitionFailureMarksOnlyThatModule() throws Exception {
        var failure = new IllegalStateException("intentional failure");
        ModuleContributions a = module("A");
        a.enchantments().register(Key.key("a:first"), (builder, tags) -> {});
        a.enchantments().register(Key.key("a:bad"), (builder, tags) -> { throw failure; });
        a.enchantments().register(Key.key("a:remaining"), (builder, tags) -> {});
        accept(a);
        ModuleContributions b = module("B");
        b.enchantments().register(Key.key("b:ok"), (builder, tags) -> {});
        accept(b);

        assertThatCode(() -> registry.composeEnchantments(writer, mock(ComposeTags.class))).doesNotThrowAnyException();
        registry.composeEnchantments(writer, mock(ComposeTags.class));
        assertThat(written).containsExactly(Key.key("a:first"), Key.key("b:ok"));
        assertThat(registry.failed(a.jar())).isTrue();
        assertThat(admission.inspect(a.jar().toFile()).getBootstrapFailure()).isSameAs(failure);
        assertThat(admission.inspect(b.jar().toFile()).getBootstrapFailure()).isNull();
    }

    @Test
    void virtualMachineErrorPropagates() throws Exception {
        ModuleContributions a = module("A");
        a.enchantments().register(Key.key("a:bad"), (builder, tags) -> { throw new StackOverflowError("fatal"); });
        accept(a);
        assertThatThrownBy(() -> registry.composeEnchantments(writer, mock(ComposeTags.class)))
                .isInstanceOf(StackOverflowError.class).hasMessage("fatal");
        assertThat(registry.failed(a.jar())).isFalse();
        assertThat(written).isEmpty();
    }

    private ModuleContributions module(String name) throws Exception {
        Path jar = BootstrapFixtures.jar(folder.resolve(name + ".jar"), BootstrapFixtures.descriptor(name, null), Map.of());
        admission.inspect(jar.toFile());
        return new ModuleContributions(name, jar);
    }

    private void accept(ModuleContributions module) {
        module.seal();
        registry.accept(module);
    }
}
