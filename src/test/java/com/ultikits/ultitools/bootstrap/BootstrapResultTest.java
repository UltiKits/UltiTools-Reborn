package com.ultikits.ultitools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.lang.reflect.Method;
import java.util.Collections;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.ModuleClassLoader;
import com.ultikits.ultitools.manager.ModuleJarAdmission;
import com.ultikits.ultitools.utils.ModuleFileTransactions;

class BootstrapResultTest {
    @TempDir Path temporary;

    @Test
    void result_preservesStageIdentitiesAndCopiesTheFailureMap() throws Exception {
        ModuleFileTransactions transactions = new ModuleFileTransactions(temporary.toFile());
        ModuleJarAdmission admission = new ModuleJarAdmission();
        IOException failure = new IOException("fixture");
        Map<Path, Throwable> failures = new LinkedHashMap<>();
        failures.put(temporary.resolve("a.jar"), failure);
        failures.put(temporary.resolve("z.jar"), failure);
        try (ModuleClassLoader loader = ModuleClassLoader.create(new URL[0], getClass().getClassLoader())) {
            BootstrapResult result = new BootstrapResult(transactions, admission, loader, failure, failures, 3,
                    new ContributionRegistry(admission, java.util.logging.Logger.getAnonymousLogger()));
            failures.clear();
            assertThat(result.transactions()).isSameAs(transactions);
            assertThat(result.admission()).isSameAs(admission);
            assertThat(result.classLoader()).isSameAs(loader);
            assertThat(result.classPathFailure()).isSameAs(failure);
            assertThat(result.bootstrapsRun()).isEqualTo(3);
            assertThat(result.bootstrapFailures().keySet())
                    .containsExactly(temporary.resolve("a.jar"), temporary.resolve("z.jar"));
            assertThatThrownBy(() -> result.bootstrapFailures().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void admission_retainsNormalizedIdentitiesAndAnImmutableFilenameOrderedSnapshot() throws Exception {
        Path z = BootstrapFixtures.jar(temporary.resolve("z.jar"), BootstrapFixtures.descriptor("Z", null),
                Collections.emptyMap());
        Path a = BootstrapFixtures.jar(temporary.resolve("a.jar"), BootstrapFixtures.descriptor("A", null),
                Collections.emptyMap());
        ModuleJarAdmission admission = new ModuleJarAdmission();
        admission.inspect(z.toFile());
        admission.inspect(a.toFile());
        Map<Path, ModuleJarAdmission.Entry> snapshot = admission.entries();
        IOException failure = new IOException("fixture");
        admission.markBootstrapFailed(temporary.resolve("unused/../a.jar"), failure);
        assertThat(snapshot.keySet()).containsExactly(a, z);
        assertThat(admission.inspect(a.toFile()).getBootstrapFailure()).isSameAs(failure);
        assertThat(snapshot.get(a)).isSameAs(admission.inspect(a.toFile()));
        assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void moduleContext_exposesOnlyTheAgreedMembersAndContentChannels() throws Exception {
        assertThat(ModuleBootstrapContext.class.getDeclaredMethods()).extracting(Method::getName)
                .containsExactlyInAnyOrder("moduleName", "moduleJar", "logger", "enchantments", "tags", "datapacks");
        assertThat(ModuleBootstrapContext.class.getMethod("enchantments").getReturnType()).isEqualTo(EnchantmentChannel.class);
        assertThat(ModuleBootstrapContext.class.getMethod("tags").getReturnType()).isEqualTo(TagChannel.class);
        assertThat(ModuleBootstrapContext.class.getMethod("datapacks").getReturnType()).isEqualTo(DatapackChannel.class);
        assertThat(ModuleBootstrap.class.getDeclaredMethod("bootstrap", ModuleBootstrapContext.class).getExceptionTypes())
                .containsExactly(Exception.class);
    }

    @Test
    void fallback_isAnEmptyModuleSet() throws Exception {
        IOException failure = new IOException("bootstrap preparation failed");
        BootstrapResult result = BootstrapResult.failed(failure);
        try (ModuleClassLoader loader = result.classLoader()) {
            assertThat(loader.getURLs()).isEmpty();
            assertThat(result.bootstrapFailures()).isEmpty();
            assertThat(result.bootstrapsRun()).isZero();
            assertThat(result.classPathFailure()).isSameAs(failure);
            assertThatThrownBy(result::transactions).isInstanceOf(IllegalStateException.class).hasCause(failure);
        }
    }
}
