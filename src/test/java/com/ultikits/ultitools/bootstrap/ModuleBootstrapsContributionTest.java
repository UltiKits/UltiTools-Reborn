package com.ultikits.ultitools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.ModuleClassLoader;
import com.ultikits.ultitools.manager.ModuleJarAdmission;

import net.kyori.adventure.key.Key;

class ModuleBootstrapsContributionTest {
    @TempDir Path folder;

    @Test
    void declarationsOfAFailedBootstrapAreDiscarded() throws Exception {
        Path jar = module("Aborts", "java/lang/IllegalStateException");
        Logger logger = Logger.getAnonymousLogger();
        ModuleJarAdmission admission = new ModuleJarAdmission();
        var entry = admission.inspect(jar.toFile());
        ContributionRegistry registry = new ContributionRegistry(admission, logger);
        try (var loader = ModuleClassLoader.create(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            var run = ModuleBootstraps.runAll(admission, loader, logger, registry);
            assertThat(registry.modules()).isEmpty();
            assertThat(entry.getBootstrapFailure()).isSameAs(run.failures.get(jar));
            assertThat(entry.getBootstrapFailure()).hasMessage("fixture failure");
            var context = (ModuleBootstrapContext) loader.loadClass("fixture.Aborts").getField("context").get(null);
            assertClosed(context);
        }
    }

    @Test
    void declarationsTakeEffectOnlyAfterNormalReturn() throws Exception {
        Path jar = module("Succeeds", null);
        Logger logger = Logger.getAnonymousLogger();
        ModuleJarAdmission admission = new ModuleJarAdmission();
        admission.inspect(jar.toFile());
        ContributionRegistry registry = new ContributionRegistry(admission, logger);
        try (var loader = ModuleClassLoader.create(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            var run = ModuleBootstraps.runAll(admission, loader, logger, registry);
            assertThat(run.failures).isEmpty();
            assertThat(registry.modules()).singleElement().satisfies(module ->
                    assertThat(module.enchantmentsByKey()).containsOnlyKeys(Key.key("succeeds:edge")));
            var context = (ModuleBootstrapContext) loader.loadClass("fixture.Succeeds").getField("context").get(null);
            assertClosed(context);
        }
    }

    private static void assertClosed(ModuleBootstrapContext context) {
        assertThatThrownBy(() -> context.enchantments().register(Key.key("another"), (builder, tags) -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("bootstrap channels are closed after bootstrap() returns");
        assertThatThrownBy(() -> context.tags().setTag(null, null, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> context.datapacks().embedded("not/a/name")).isInstanceOf(IllegalStateException.class);
    }

    private Path module(String name, String failure) throws Exception {
        String hook = "fixture." + name;
        return BootstrapFixtures.jar(folder.resolve(name + ".jar"), BootstrapFixtures.descriptor(name, hook),
                Map.of(hook, BootstrapFixtures.contentHook(hook, failure)));
    }
}
