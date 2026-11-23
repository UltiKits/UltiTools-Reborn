package com.ultikits.ultitools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
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
            BootstrapResult result = new BootstrapResult(transactions, admission, loader, failure, failures, 3);
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
    void fallback_isAnEmptyModuleSet() throws Exception {
        BootstrapResult result = BootstrapResult.empty(temporary.toFile(), null);
        try (ModuleClassLoader loader = result.classLoader()) {
            assertThat(loader.getURLs()).isEmpty();
            assertThat(result.bootstrapFailures()).isEmpty();
            assertThat(result.bootstrapsRun()).isZero();
            assertThat(result.classPathFailure()).isNull();
            assertThat(result.transactions()).isNotNull();
        }
    }
}
