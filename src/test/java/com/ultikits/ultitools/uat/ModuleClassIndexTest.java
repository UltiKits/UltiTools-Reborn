package com.ultikits.ultitools.uat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the extractor's core security property (Phase 10, T-10-01): resolving a class via
 * {@link ModuleClassIndex#load} never runs its static initializer, even when that initializer
 * would throw.
 * <p>
 * The throwing fixture is compiled AT TEST TIME into a {@code @TempDir} scratch tree and loaded
 * through a fresh, throwaway {@link URLClassLoader} scoped to only that directory -- it is
 * never a persistent {@code .class} file anywhere under {@code target/test-classes}. This is
 * deliberate, not incidental: {@code PackageScanUtilsTest.shouldScanVariousPackageLevels} (and
 * its sibling {@code emptyPackageNameShouldNotThrow}) already exercise
 * {@code PackageScanUtils.scanAnnotatedClasses} against the literal packages {@code "com.ultikits"}
 * and {@code "com.ultikits.ultitools"} using the ordinary system/test classloader --
 * {@code PackageScanUtils} resolves every class Guava's {@code ClassPath} finds via
 * {@code Class.forName(name, true, loader)}, WITH initialization. A persistent throwing fixture
 * anywhere under {@code com.ultikits.*} is therefore initialized as a side effect of that
 * unrelated, pre-existing test the very first time either test runs in the shared JVM `mvn test`
 * uses -- observed directly: a fixture placed at
 * {@code com.ultikits.ultitools.uat.fixtures.staticinit.ThrowingStaticInit} passed reliably
 * under a filtered `-Dtest=...` run naming only this class, and failed under the full suite with
 * its witness field already tripped before this test's own body ever ran. Compiling into an
 * isolated classloader that no other test's classpath scan can ever see removes the hazard
 * structurally rather than working around one specific scan's package argument.
 */
@DisplayName("ModuleClassIndex")
class ModuleClassIndexTest {

    private static final String FIXTURE_SOURCE =
            "package com.ultikits.ultitools.uat.isolatedfixture;\n"
                    + "public final class ThrowingStaticInit {\n"
                    + "    static {\n"
                    + "        if (Boolean.TRUE) {\n"
                    + "            throw new ExceptionInInitializerError("
                    + "\"ThrowingStaticInit must never initialize during extraction\");\n"
                    + "        }\n"
                    + "    }\n"
                    + "    private ThrowingStaticInit() {\n"
                    + "    }\n"
                    + "}\n";

    private static final String FIXTURE_BINARY_NAME =
            "com.ultikits.ultitools.uat.isolatedfixture.ThrowingStaticInit";

    @Test
    @DisplayName("resolves a class without running its static initializer")
    void doesNotTriggerStaticInitializer(@TempDir Path scratchRoot) throws Exception {
        compileFixtureInto(scratchRoot);

        try (URLClassLoader isolatedLoader = new URLClassLoader(
                new URL[] {scratchRoot.toUri().toURL()}, ClassLoader.getSystemClassLoader())) {
            List<Class<?>> classes = new ModuleClassIndex(isolatedLoader).load(scratchRoot);

            assertThat(classes).hasSize(1);
            assertThat(classes.get(0).getName()).isEqualTo(FIXTURE_BINARY_NAME);

            // Prove the negative is not vacuous: this loader/class pair has never been touched
            // by anything else (it exists only in this @TempDir, compiled fresh above), so this
            // is the first-ever real initialization attempt -- it must throw.
            assertThatThrownBy(() -> Class.forName(FIXTURE_BINARY_NAME, true, isolatedLoader))
                    .isInstanceOf(ExceptionInInitializerError.class);
        }
    }

    @Test
    @DisplayName("rejects a --classes argument that is neither an existing directory nor an existing jar")
    void rejectsMissingClassesPath() {
        Path missing = Paths.get("this/path/does/not/exist/anywhere");

        assertThatThrownBy(() -> new ModuleClassIndex(Thread.currentThread().getContextClassLoader()).load(missing))
                .isInstanceOf(ExtractorException.class);
    }

    private static void compileFixtureInto(Path scratchRoot) throws Exception {
        Path sourceFile = scratchRoot.resolve("ThrowingStaticInit.java");
        Files.write(sourceFile, FIXTURE_SOURCE.getBytes(StandardCharsets.UTF_8));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("a JDK (not just a JRE) is required to run this test").isNotNull();
        int result = compiler.run(null, null, null,
                "-d", scratchRoot.toString(), sourceFile.toString());
        assertThat(result).as("compiling the scratch fixture").isZero();
        Files.delete(sourceFile);
    }
}
