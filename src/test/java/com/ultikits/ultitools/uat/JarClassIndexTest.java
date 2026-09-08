package com.ultikits.ultitools.uat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@link ModuleClassIndex}'s jar input form (Phase 10 plan 10-02, Task 3): a jar and a
 * directory holding identical classes produce byte-identical {@link SurfaceAssembler} documents,
 * a non-class jar entry is skipped without error, and a {@code --classes} argument naming
 * neither an existing directory nor an existing jar fails closed.
 * <p>
 * The fixture is compiled fresh into a scratch {@code @TempDir}, never shipped as a persistent
 * {@code .class} file — this is deliberate, not incidental. A fixture already present on the
 * ambient test classpath (like the tracer fixtures used elsewhere in this package) would resolve
 * via the parent classloader's own parent-first delegation regardless of whether
 * {@code ModuleClassIndex} adds {@code classesRoot} to its own resolution path, silently making
 * this test pass whether or not that self-sufficiency actually exists. Compiling into an
 * isolated, never-otherwise-referenced package removes that false-positive path structurally: the
 * fixture is reachable ONLY through whatever loader {@code ModuleClassIndex} itself builds.
 */
@DisplayName("ModuleClassIndex jar input")
class JarClassIndexTest {

    private static final String FIXTURE_PACKAGE = "com.ultikits.ultitools.uat.isolatedfixture";
    private static final String FIXTURE_SIMPLE_NAME = "IsolatedTableFixture";
    private static final String FIXTURE_BINARY_NAME = FIXTURE_PACKAGE + "." + FIXTURE_SIMPLE_NAME;
    private static final String FIXTURE_ENTRY_NAME = FIXTURE_BINARY_NAME.replace('.', '/') + ".class";
    private static final String FIXTURE_SOURCE =
            "package " + FIXTURE_PACKAGE + ";\n"
                    + "import com.ultikits.ultitools.annotations.Table;\n"
                    + "@Table(\"isolated_table\")\n"
                    + "public final class " + FIXTURE_SIMPLE_NAME + " {\n"
                    + "}\n";

    @Test
    @DisplayName("a jar and a directory holding the same class produce byte-identical documents")
    void jarAndDirectoryProduceByteIdenticalDocuments(@TempDir Path scratchRoot) throws Exception {
        byte[] classBytes = compileFixture(scratchRoot);

        Path directoryRoot = scratchRoot.resolve("dir");
        writeClassIntoDirectory(directoryRoot, classBytes);

        Path jarPath = scratchRoot.resolve("fixture.jar");
        writeClassIntoJar(jarPath, classBytes);

        String fromDirectory = assembleJson("Fixture", directoryRoot);
        String fromJar = assembleJson("Fixture", jarPath);

        assertThat(fromJar).isEqualTo(fromDirectory);
        assertThat(fromJar).contains("\"kind\": \"persistence\"");
        assertThat(fromJar).contains("\"table\": \"isolated_table\"");
    }

    @Test
    @DisplayName("resolution needs no help from the caller's ambient classpath -- classesRoot alone is self-sufficient")
    void classesRootIsSelfSufficientEvenWithABareParentLoader(@TempDir Path scratchRoot) throws Exception {
        byte[] classBytes = compileFixture(scratchRoot);
        Path directoryRoot = scratchRoot.resolve("dir");
        writeClassIntoDirectory(directoryRoot, classBytes);

        // A parent loader that can see the JDK but nothing of this project or its dependencies --
        // proves the fixture resolves purely off classesRoot, not off whatever happens to already
        // be on the caller's own -cp.
        try (URLClassLoader bareParent = new URLClassLoader(new URL[0], ClassLoader.getSystemClassLoader().getParent())) {
            List<Class<?>> classes = new ModuleClassIndex(bareParent).load(directoryRoot);
            assertThat(classes).extracting(Class::getName).containsExactly(FIXTURE_BINARY_NAME);
        }
    }

    @Test
    @DisplayName("a multi-release jar's versioned overlay entry is skipped, not a fatal invalid binary name")
    void multiReleaseOverlayEntryIsSkippedNotFatal(@TempDir Path scratchRoot) throws Exception {
        byte[] classBytes = compileFixture(scratchRoot);
        Path jarPath = scratchRoot.resolve("multi-release-fixture.jar");
        try (OutputStream fileOut = Files.newOutputStream(jarPath);
                JarOutputStream jarOut = new JarOutputStream(fileOut)) {
            jarOut.putNextEntry(new JarEntry(FIXTURE_ENTRY_NAME));
            jarOut.write(classBytes);
            jarOut.closeEntry();

            // A shaded multi-release jar carries a versioned overlay of the SAME class under
            // META-INF/versions/<N>/... -- converting that path verbatim would produce the
            // invalid binary name "META-INF.versions.11...", which used to fail extraction
            // closed for the whole jar rather than merely skip this one overlay entry.
            jarOut.putNextEntry(new JarEntry("META-INF/versions/11/" + FIXTURE_ENTRY_NAME));
            jarOut.write(classBytes);
            jarOut.closeEntry();
        }

        List<Class<?>> classes = new ModuleClassIndex(Thread.currentThread().getContextClassLoader()).load(jarPath);

        assertThat(classes).extracting(Class::getName).containsExactly(FIXTURE_BINARY_NAME);
    }

    @Test
    @DisplayName("a class that resolves from the PARENT loader instead of the requested classesRoot fails closed, naming both locations")
    void classResolvingFromTheParentLoaderInsteadOfClassesRootFailsClosed(@TempDir Path scratchRoot) throws Exception {
        // A standard URLClassLoader delegates to its parent FIRST (Codex review of PR #427).
        // com.ultikits.ultitools.uat.fixtures.TracerCommands is a REAL fixture already compiled
        // onto this test run's own ambient classpath -- reusing its exact binary name here
        // reproduces the documented UltiBot multi-module scenario (a sibling reactor module's
        // class, or a previously installed version, also reachable through the caller's -cp)
        // without needing a real multi-module build: whatever this scratch directory contains
        // under that name is NEVER what actually resolves, because the parent (this test JVM's
        // own classpath) already has a real, different TracerCommands.class that answers first.
        String shadowedBinaryName = "com.ultikits.ultitools.uat.fixtures.TracerCommands";
        String shadowedEntryName = shadowedBinaryName.replace('.', '/') + ".class";
        String shadowSource =
                "package com.ultikits.ultitools.uat.fixtures;\n"
                        + "public final class TracerCommands {\n"
                        + "}\n";

        Path compileDir = scratchRoot.resolve("shadow-compile-out");
        Files.createDirectories(compileDir);
        Path sourceFile = scratchRoot.resolve("TracerCommands.java");
        Files.write(sourceFile, shadowSource.getBytes(StandardCharsets.UTF_8));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("a JDK (not just a JRE) is required to run this test").isNotNull();
        int result = compiler.run(null, null, null, "-d", compileDir.toString(), sourceFile.toString());
        assertThat(result).as("compiling the shadow fixture").isZero();
        byte[] shadowClassBytes = Files.readAllBytes(
                compileDir.resolve(shadowedEntryName.replace('/', java.io.File.separatorChar)));

        Path shadowDirectoryRoot = scratchRoot.resolve("shadow-dir");
        Path target = shadowDirectoryRoot.resolve(shadowedEntryName);
        Files.createDirectories(target.getParent());
        Files.write(target, shadowClassBytes);

        ModuleClassIndex index = new ModuleClassIndex(Thread.currentThread().getContextClassLoader());

        assertThatThrownBy(() -> index.load(shadowDirectoryRoot))
                .isInstanceOf(ExtractorException.class)
                .hasMessageContaining(shadowedBinaryName)
                .hasMessageContaining("did not resolve from the requested classesRoot");
    }

    @Test
    @DisplayName("a --classes argument that is neither an existing directory nor an existing jar exits non-zero naming the argument")
    void rejectsArgumentThatIsNeitherDirectoryNorJar() {
        Path missing = Paths.get("this/path/does/not/exist/anywhere.jar");

        assertThatThrownBy(() -> new ModuleClassIndex(Thread.currentThread().getContextClassLoader()).load(missing))
                .isInstanceOf(ExtractorException.class)
                .hasMessageContaining(missing.toString());
    }

    private static String assembleJson(String origin, Path classesRoot) throws ExtractorException {
        ModuleClassIndex index = new ModuleClassIndex(Thread.currentThread().getContextClassLoader());
        List<Class<?>> classes = index.load(classesRoot);
        SurfaceAssembler.AssembledSurface surface = new SurfaceAssembler().assemble(origin, classes);
        return CanonicalJsonWriter.toJsonString(1, surface.getRows(), surface.getDocumentExtras());
    }

    private static byte[] compileFixture(Path scratchRoot) throws IOException {
        Path compileDir = scratchRoot.resolve("compile-out");
        Files.createDirectories(compileDir);
        Path sourceFile = scratchRoot.resolve(FIXTURE_SIMPLE_NAME + ".java");
        Files.write(sourceFile, FIXTURE_SOURCE.getBytes(StandardCharsets.UTF_8));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("a JDK (not just a JRE) is required to run this test").isNotNull();
        int result = compiler.run(null, null, null, "-d", compileDir.toString(), sourceFile.toString());
        assertThat(result).as("compiling the scratch fixture").isZero();

        Path compiledClassFile = compileDir.resolve(FIXTURE_ENTRY_NAME.replace('/', java.io.File.separatorChar));
        return Files.readAllBytes(compiledClassFile);
    }

    private static void writeClassIntoDirectory(Path directoryRoot, byte[] classBytes) throws IOException {
        Path target = directoryRoot.resolve(FIXTURE_ENTRY_NAME);
        Files.createDirectories(target.getParent());
        Files.write(target, classBytes);
    }

    private static void writeClassIntoJar(Path jarPath, byte[] classBytes) throws IOException {
        try (OutputStream fileOut = Files.newOutputStream(jarPath);
                JarOutputStream jarOut = new JarOutputStream(fileOut)) {
            jarOut.putNextEntry(new JarEntry(FIXTURE_ENTRY_NAME));
            jarOut.write(classBytes);
            jarOut.closeEntry();

            // A jar entry that is not a class file must be skipped without error (Phase 10
            // plan 10-02, Task 3 behavior) -- this is the specimen proving it.
            jarOut.putNextEntry(new JarEntry("uat/fixture-notes.txt"));
            jarOut.write("not a class file".getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
    }
}
