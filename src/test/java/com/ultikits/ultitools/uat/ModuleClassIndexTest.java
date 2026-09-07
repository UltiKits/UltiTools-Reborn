package com.ultikits.ultitools.uat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the extractor's core security property (Phase 10, T-10-01): resolving a class via
 * {@link ModuleClassIndex#load} never runs its static initializer, even when that initializer
 * would throw.
 * <p>
 * {@code ThrowingStaticInit}'s real compiled {@code .class} file is copied into a scratch
 * directory that mirrors its package path ({@code com/ultikits/.../staticinit/}) rather than
 * scanned in place under {@code target/test-classes}: {@link ModuleClassIndex#load} computes a
 * binary name by relativizing each {@code .class} file against the passed-in root, so the root
 * must sit ABOVE the package structure, exactly like a real module's own {@code target/classes}.
 * Copying keeps the scan narrow (this one class) while still exercising the real relativize path.
 */
@DisplayName("ModuleClassIndex")
class ModuleClassIndexTest {

    @Test
    @DisplayName("resolves a class without running its static initializer")
    void doesNotTriggerStaticInitializer(@TempDir Path scratchRoot) throws Exception {
        Path scratchClassesRoot = copyStaticInitFixtureInto(scratchRoot);

        List<Class<?>> classes = new ModuleClassIndex(Thread.currentThread().getContextClassLoader())
                .load(scratchClassesRoot);

        assertThat(classes).hasSize(1);
        assertThat(classes.get(0).getName())
                .isEqualTo("com.ultikits.ultitools.uat.fixtures.staticinit.ThrowingStaticInit");

        // Prove the negative is not vacuous: forcing real initialization now must throw.
        assertThatThrownBy(() -> Class.forName(
                "com.ultikits.ultitools.uat.fixtures.staticinit.ThrowingStaticInit",
                true,
                Thread.currentThread().getContextClassLoader()))
                .isInstanceOf(ExceptionInInitializerError.class);
    }

    @Test
    @DisplayName("rejects a --classes argument that is neither an existing directory nor an existing jar")
    void rejectsMissingClassesPath() {
        Path missing = Paths.get("this/path/does/not/exist/anywhere");

        assertThatThrownBy(() -> new ModuleClassIndex(Thread.currentThread().getContextClassLoader()).load(missing))
                .isInstanceOf(ExtractorException.class);
    }

    /**
     * Copies the real, already-compiled {@code ThrowingStaticInit.class} into
     * {@code scratchRoot/com/ultikits/ultitools/uat/fixtures/staticinit/}, returning
     * {@code scratchRoot} (the directory a real module's {@code --classes} argument would name).
     */
    private static Path copyStaticInitFixtureInto(Path scratchRoot) throws URISyntaxException, IOException {
        URL compiled = ModuleClassIndexTest.class.getClassLoader()
                .getResource("com/ultikits/ultitools/uat/fixtures/staticinit/ThrowingStaticInit.class");
        assertThat(compiled).as("compiled ThrowingStaticInit.class on the test classpath").isNotNull();
        Path compiledClassFile = Paths.get(compiled.toURI());

        Path targetDir = scratchRoot.resolve("com/ultikits/ultitools/uat/fixtures/staticinit");
        Files.createDirectories(targetDir);
        Files.copy(compiledClassFile, targetDir.resolve("ThrowingStaticInit.class"), StandardCopyOption.REPLACE_EXISTING);
        return scratchRoot;
    }
}
