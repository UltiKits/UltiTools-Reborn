package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ultikits.ultitools.bootstrap.BootstrapFixtures;
import com.ultikits.ultitools.bootstrap.ModuleClassPath;
import com.ultikits.ultitools.manager.ModuleJarAdmission;

class ModuleClassLoaderDependencyTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"depend", "softdepend"})
    void declaredPluginClass_isVisible(String key) throws Exception {
        ModuleJarAdmission admission = new ModuleJarAdmission();
        Path module = probe("Probe", key + ": [DepTarget]\n");
        try (URLClassLoader provider = provider(); ModuleClassLoader loader = modules(admission)) {
            loader.addDeclaredDependencies(admission, name -> "DepTarget".equals(name) ? provider : null);
            Object found = loader.loadClass("fixture.ProbeMain").getMethod("dependency").invoke(null);
            assertThat(found).isEqualTo(provider.loadClass("fixture.dep.Api"));
            assertThat(module).exists();
        }
    }

    @Test
    void undeclaredPluginClass_isNotVisible() throws Exception {
        probe("Probe", "");
        ModuleJarAdmission admission = new ModuleJarAdmission();
        List<String> requested = new ArrayList<>();
        try (URLClassLoader provider = provider(); ModuleClassLoader loader = modules(admission)) {
            assertThat(provider.loadClass("fixture.dep.Api").getClassLoader()).isSameAs(provider);
            loader.addDeclaredDependencies(admission, name -> {
                requested.add(name);
                return "DepTarget".equals(name) ? provider : null;
            });
            assertThatThrownBy(() -> loader.loadClass("fixture.ProbeMain").getMethod("dependency").invoke(null))
                    .cause().isInstanceOf(ClassNotFoundException.class)
                    .hasMessageContaining("Declare the providing plugin").hasMessageContaining("depend or softdepend")
                    .hasMessageContaining("not guaranteed");
            assertThat(requested).doesNotContain("DepTarget");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusedModuleDeclaration_doesNotGrantVisibility(boolean bootstrapFailed) throws Exception {
        probe("Probe", "");
        Path refusing = BootstrapFixtures.jar(temporary.resolve("modules/refused.jar"),
                BootstrapFixtures.descriptor("Refused", null).replace("700", bootstrapFailed ? "700" : "630")
                        + "softdepend: [DepTarget]\n", Collections.emptyMap());
        ModuleJarAdmission admission = new ModuleJarAdmission();
        try (URLClassLoader provider = provider(); ModuleClassLoader loader = modules(admission)) {
            if (bootstrapFailed) {
                admission.markBootstrapFailed(refusing, new NoClassDefFoundError("fixture failure"));
            }
            List<String> requested = new ArrayList<>();
            loader.addDeclaredDependencies(admission, name -> {
                requested.add(name);
                return "DepTarget".equals(name) ? provider : null;
            });
            assertThat(requested).doesNotContain("DepTarget");
            assertThatThrownBy(() -> loader.loadClass("fixture.dep.Api")).isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    void absentSoftDependency_doesNotPreventLoading() throws Exception {
        probe("Probe", "softdepend: [AbsentPlugin]\n");
        ModuleJarAdmission admission = new ModuleJarAdmission();
        List<String> requested = new ArrayList<>();
        try (ModuleClassLoader loader = modules(admission)) {
            loader.addDeclaredDependencies(admission, name -> { requested.add(name); return null; });
            assertThat(loader.loadClass("fixture.ProbeMain").getSuperclass().getName())
                    .isEqualTo("com.ultikits.ultitools.abstracts.UltiToolsPlugin");
            assertThat(requested).contains("AbsentPlugin");
            assertThat(admission.entries().values()).allMatch(entry -> entry.getBootstrapFailure() == null);
        }
    }

    @Test
    void dependencyNames_areSortedAndTheSameLoaderIsOnlyTriedOnce() throws Exception {
        probe("Probe", "depend: [TargetZ, TargetA, TargetZ]\nsoftdepend: [TargetA]\n");
        ModuleJarAdmission admission = new ModuleJarAdmission();
        ClassLoader provider = mock(ClassLoader.class);
        when(provider.loadClass("missing.Dep")).thenThrow(new ClassNotFoundException("missing.Dep"));
        List<String> requested = new ArrayList<>();
        try (ModuleClassLoader loader = modules(admission)) {
            loader.addDeclaredDependencies(admission, name -> { requested.add(name); return provider; });
            assertThat(requested).containsExactlyElementsOf(Arrays.asList("PlaceholderAPI", "TargetA", "TargetZ", "Vault"));
            assertThatThrownBy(() -> loader.loadClass("missing.Dep")).isInstanceOf(ClassNotFoundException.class);
            verify(provider, times(1)).loadClass("missing.Dep");
        }
    }

    private Path probe(String name, String dependencies) throws Exception {
        return BootstrapFixtures.jar(temporary.resolve("modules/" + name + ".jar"),
                BootstrapFixtures.descriptor(name, null) + dependencies,
                Collections.singletonMap("fixture." + name + "Main", BootstrapFixtures.dependencyProbe("fixture." + name + "Main")));
    }

    private URLClassLoader provider() throws Exception {
        Path jar = BootstrapFixtures.jar(temporary.resolve("provider/DepTarget.jar"),
                BootstrapFixtures.descriptor("DepTarget", null),
                Collections.singletonMap("fixture.dep.Api", BootstrapFixtures.plainClass("fixture.dep.Api")));
        return new URLClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader());
    }

    private ModuleClassLoader modules(ModuleJarAdmission admission) {
        List<URL> urls = ModuleClassPath.collectModuleJarUrls(temporary.resolve("modules").toFile(), admission);
        return ModuleClassLoader.create(urls.toArray(new URL[0]), getClass().getClassLoader());
    }
}
