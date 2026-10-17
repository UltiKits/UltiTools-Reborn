package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.lang.model.SourceVersion;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Observable declaration, documentation and reflection contracts for the build-only scanner. */
class JavadocDeprecationScannerTest {
    private static final String MATRIX = "com.ultikits.testfixtures.deprecationscan.DeclarationMatrixSubject";
    private static final Path FIXTURES = Paths.get("src/test/java/com/ultikits/testfixtures/deprecationscan");

    private List<DeprecationEntry> matrixEntries() throws IOException {
        return new JavadocDeprecationScanner(getClass().getClassLoader()).scan(FIXTURES);
    }

    private DeprecationEntry exactEntry(String key) throws IOException {
        List<DeprecationEntry> matches = matrixEntries().stream()
                .filter(e -> e.getKey().toString().equals(key)).collect(Collectors.toList());
        assertThat(matches).as("unique exact key %s", key).hasSize(1);
        return matches.get(0);
    }

    @Test
    void documentedMemberIsPositiveControl() throws IOException {
        DeprecationEntry entry = exactEntry(MATRIX + "#documented()");
        assertThat(entry.getKind()).isEqualTo(DeprecationEntry.Kind.METHOD);
        assertThat(entry.getSince()).isEqualTo("6.2.3");
        assertThat(entry.isForRemoval()).isTrue();
        assertThat(entry.getRemoveIn()).isEqualTo("7.0.0");
        assertThat(entry.getReplacement()).isEqualTo("Use String instead.");
        assertThat(entry.getStatus()).isEqualTo(DeprecationEntry.Status.ANNOUNCED);
    }

    @Test
    void annotationOnlyDeclarationIsRegistered() throws IOException {
        DeprecationEntry entry = exactEntry(MATRIX + "#annotationOnly()");
        assertThat(entry.getKind()).isEqualTo(DeprecationEntry.Kind.METHOD);
        assertThat(entry.getSince()).isEqualTo("6.2.0");
        assertThat(entry.getReplacement()).isEmpty();
        assertThat(entry.getRemoveIn()).isNull();
        assertThat(entry.getStatus()).isEqualTo(DeprecationEntry.Status.DEPRECATED);
        assertThat(exactEntry(MATRIX + "#ordinaryDoc()").getSince()).isEqualTo("6.2.1");
    }

    @Test
    void initializedFieldHasFieldKey() throws IOException {
        for (String field : Arrays.asList("initializedField", "constructorField", "factoryField", "arrayField")) {
            DeprecationEntry entry = exactEntry(MATRIX + "#" + field);
            assertThat(entry.getKind()).isEqualTo(DeprecationEntry.Kind.FIELD);
            assertThat(entry.getKey().isField()).isTrue();
            assertThat(entry.getKey().getParameterTypes()).isNull();
        }
        assertThat(exactEntry(MATRIX + "#initializedField").getSince()).isEqualTo("6.2.2");
    }

    @Test
    void exactCanonicalKeysCoverEverySignatureFamily() throws IOException {
        Map<String, DeprecationEntry.Kind> expected = new LinkedHashMap<>();
        expected.put(MATRIX, DeprecationEntry.Kind.CLASS);
        expected.put(MATRIX + "#DeclarationMatrixSubject()", DeprecationEntry.Kind.CONSTRUCTOR);
        expected.put(MATRIX + "#DeclarationMatrixSubject(java.lang.String,int[])", DeprecationEntry.Kind.CONSTRUCTOR);
        expected.put(MATRIX + "#overload(java.util.Date)", DeprecationEntry.Kind.METHOD);
        expected.put(MATRIX + "#overload(java.sql.Date)", DeprecationEntry.Kind.METHOD);
        expected.put(MATRIX + "#arrays(int[][],java.lang.String[],java.lang.Number[],java.util.Map$Entry[])",
                DeprecationEntry.Kind.METHOD);
        expected.put(MATRIX + "#shadow(java.lang.CharSequence,java.lang.Number[])", DeprecationEntry.Kind.METHOD);
        expected.put(MATRIX + "#unbounded(java.lang.Object,java.lang.Object[])", DeprecationEntry.Kind.METHOD);
        expected.put(MATRIX + "#parameterized(java.util.List," + MATRIX + "$Named$InnerType)", DeprecationEntry.Kind.METHOD);
        expected.put(MATRIX + "$Named", DeprecationEntry.Kind.CLASS);
        expected.put(MATRIX + "$Named#Named()", DeprecationEntry.Kind.CONSTRUCTOR);
        expected.put(MATRIX + "$Named#nested(" + MATRIX + "$Named$InnerType)", DeprecationEntry.Kind.METHOD);
        expected.put(MATRIX + "$Inner#Inner(" + MATRIX + ",java.lang.String)", DeprecationEntry.Kind.CONSTRUCTOR);
        List<DeprecationEntry> entries = matrixEntries();
        expected.forEach((key, kind) -> {
            List<DeprecationEntry> matches = entries.stream().filter(e -> e.getKey().toString().equals(key))
                    .collect(Collectors.toList());
            assertThat(matches).as("exact signature %s", key).hasSize(1);
            assertThat(matches.get(0).getKind()).isEqualTo(kind);
        });
    }

    @Test
    void customAnnotationAndLocalBodiesDoNotCreateApiEntries() throws IOException {
        List<String> keys = matrixEntries().stream().map(e -> e.getKey().toString()).collect(Collectors.toList());
        assertThat(keys).contains(MATRIX + "$Shadows#real()", MATRIX + "#annotationOnly()");
        assertThat(keys).doesNotContain(MATRIX + "$Shadows#custom()");
        assertThat(keys).noneMatch(k -> k.contains("#local") || k.contains("$Local") || k.contains("#hidden("));
    }

    @Test
    void fakeInlineTagsDoNotBecomeMetadata() throws IOException {
        DeprecationEntry entry = exactEntry(MATRIX + "#inlineProse()");
        assertThat(entry.getReplacement()).isEmpty();
        assertThat(entry.getRemoveIn()).isNull();
        assertThat(entry.getSince()).isNull();
    }

    @Test
    void unbalancedParenInsideStringArgument() throws IOException {
        DeprecationEntry entry = exactEntry("com.ultikits.testfixtures.deprecationscan.TrickyAnnotationSubject"
                + "#unbalancedParenInSinceValue()");
        assertThat(entry.getSince()).isEqualTo("6.2.0 (unclosed");
        assertThat(entry.isForRemoval()).isTrue();
    }

    @Test
    void semicolonInsideStringStillYieldsRightEntry() throws IOException {
        assertThat(exactEntry("com.ultikits.testfixtures.deprecationscan.TrickyAnnotationSubject"
                + "#semicolonInStringBeforeTerminator").getSince()).isEqualTo("6.2.1");
    }

    @Test
    void bareDeprecatedIsRecorded() throws IOException {
        assertThat(exactEntry("com.ultikits.testfixtures.deprecationscan.TrickyAnnotationSubject"
                + "#bareAnnotation()").getKind()).isEqualTo(DeprecationEntry.Kind.METHOD);
    }

    @Test
    void unavailableParserFailsContextually(@TempDir Path root) {
        assertThatThrownBy(() -> new JavadocDeprecationScanner(getClass().getClassLoader(), () -> null).scan(root))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("full JDK 21")
                .hasMessageContaining(root.toString());
    }

    @Test
    void malformedSourceFailsBeforeReflection(@TempDir Path root) throws IOException {
        write(root.resolve("Broken.java"), "@Deprecated class MissingOwner { @Deprecated void broken( }");
        assertThatThrownBy(() -> new JavadocDeprecationScanner(getClass().getClassLoader()).scan(root))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Malformed Java source")
                .hasMessageContaining("Broken.java:1:").hasMessageNotContaining("Cannot map declaration");
    }

    @Test
    void missingCompiledOwnerFailsContextually(@TempDir Path root) throws IOException {
        write(root.resolve("Missing.java"), "package absent; @Deprecated public class Missing {}");
        assertThatThrownBy(() -> new JavadocDeprecationScanner(getClass().getClassLoader()).scan(root))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Missing.java:1")
                .hasMessageContaining("absent.Missing CLASS Missing");
    }

    @Test
    void unmatchedMethodReportsSourceAndCandidates(@TempDir Path root) throws Exception {
        Path source = write(root.resolve("source/probe/Mismatch.java"),
                "package probe; public class Mismatch { @Deprecated public void old(String value) {} }");
        Path classes = compile(root, source);
        write(source, "package probe; public class Mismatch { @Deprecated public void old(Integer value) {} }");
        try (URLClassLoader loader = loader(classes)) {
            assertThatThrownBy(() -> new JavadocDeprecationScanner(loader).scan(source.getParent()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Mismatch.java:1")
                    .hasMessageContaining("probe.Mismatch METHOD old")
                    .hasMessageContaining("No reflected member matched").hasMessageContaining("candidates:")
                    .hasMessageContaining("java.lang.Integer").hasMessageContaining("java.lang.String");
        }
    }

    @Test
    void unmatchedFieldReportsCandidates(@TempDir Path root) throws Exception {
        Path source = write(root.resolve("source/probe/Mismatch.java"),
                "package probe; public class Mismatch { @Deprecated public String retained; }");
        Path classes = compile(root, source);
        write(source, "package probe; public class Mismatch { @Deprecated public String missing; }");
        try (URLClassLoader loader = loader(classes)) {
            assertThatThrownBy(() -> new JavadocDeprecationScanner(loader).scan(source.getParent()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Mismatch.java:1")
                    .hasMessageContaining("probe.Mismatch VARIABLE missing")
                    .hasMessageContaining("No reflected field matched").hasMessageContaining("candidates:")
                    .hasMessageContaining("probe.Mismatch.retained");
        }
    }

    @Test
    void ambiguousCorrespondenceNeverSelectsFirstCandidate(@TempDir Path root) throws Exception {
        Path source = write(root.resolve("source/probe/Ambiguous.java"), "package probe; public class Ambiguous {"
                + " @Deprecated public void date(java.util.Date d) {}"
                + " @Deprecated public void date(java.sql.Date d) {} }");
        Path classes = compile(root, source);
        // Deliberately inconsistent source/bytecode: parse is legal, attribution is ambiguous.
        write(source, "package probe; import java.util.*; import java.sql.*; public class Ambiguous {"
                + " @Deprecated public void date(Date d) {} }");
        try (URLClassLoader loader = loader(classes)) {
            assertThatThrownBy(() -> new JavadocDeprecationScanner(loader).scan(source.getParent()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Ambiguous.java:1")
                    .hasMessageContaining("probe.Ambiguous METHOD date").hasMessageContaining("Ambiguous match")
                    .hasMessageContaining("candidates:").hasMessageContaining("java.util.Date")
                    .hasMessageContaining("java.sql.Date");
        }
    }

    @Test
    void sourceAndBytecodeMustAgreeOnRealDeprecatedAnnotation(@TempDir Path root) throws Exception {
        Path source = write(root.resolve("source/probe/NotDeprecated.java"),
                "package probe; public class NotDeprecated { public void plain() {} }");
        Path classes = compile(root, source);
        write(source, "package probe; public class NotDeprecated { @Deprecated public void plain() {} }");
        try (URLClassLoader loader = loader(classes)) {
            assertThatThrownBy(() -> new JavadocDeprecationScanner(loader).scan(source.getParent()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("NotDeprecated.java")
                    .hasMessageContaining("Compiled declaration lacks java.lang.Deprecated")
                    .hasMessageContaining("probe.NotDeprecated#plain()");
        }
    }

    @Test
    void scanningDoesNotRunLiveProcessorOrInitializerSentinels(@TempDir Path root) throws Exception {
        Path markers = Files.createDirectories(root.resolve("markers"));
        String marker = markers.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        Path subject = write(root.resolve("source/probe/SafetySubject.java"),
                "package probe; @Deprecated public class SafetySubject {"
                        + " static { mark(\"static\"); } @Deprecated public static int field = mark(\"field\");"
                        + " @Deprecated public SafetySubject() { mark(\"constructor\"); }"
                        + " @Deprecated public static void touch() { mark(\"method\"); }"
                        + " private static int mark(String name) { try { java.nio.file.Files.write("
                        + "java.nio.file.Paths.get(\"" + marker + "\", name), new byte[]{1}); return 1;"
                        + " } catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); } } }");
        Path processor = write(root.resolve("processor/probe/SentinelProcessor.java"),
                "package probe; @javax.annotation.processing.SupportedAnnotationTypes(\"*\")"
                        + " @javax.annotation.processing.SupportedSourceVersion(javax.lang.model.SourceVersion.RELEASE_21)"
                        + " public class SentinelProcessor extends javax.annotation.processing.AbstractProcessor {"
                        + " public boolean process(java.util.Set<? extends javax.lang.model.element.TypeElement> a,"
                        + " javax.annotation.processing.RoundEnvironment r) { try { java.nio.file.Files.write("
                        + "java.nio.file.Paths.get(\"" + marker + "\", \"processor\"), new byte[]{1});"
                        + " } catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); } return false; } }");
        Path classes = compile(root, subject, processor);
        write(classes.resolve("META-INF/services/javax.annotation.processing.Processor"), "probe.SentinelProcessor\n");
        Path output = Files.createDirectories(root.resolve("scan-output"));
        WatchingCompiler compiler = new WatchingCompiler(classes, output);
        Map<String, String> before = snapshot(root);
        try (URLClassLoader scanLoader = loader(classes)) {
            List<DeprecationEntry> entries = new JavadocDeprecationScanner(scanLoader, () -> compiler)
                    .scan(subject.getParent());
            assertThat(entries).extracting(e -> e.getKey().toString()).containsExactly(
                    "probe.SafetySubject", "probe.SafetySubject#field", "probe.SafetySubject#SafetySubject()",
                    "probe.SafetySubject#touch()");
        }
        assertThat(compiler.options).contains("-proc:none");
        assertThat(snapshot(root)).as("scanner cannot write markers, generated source or class output")
                .isEqualTo(before);
        try (Stream<Path> files = Files.list(markers)) {
            assertThat(files.collect(Collectors.toList())).isEmpty();
        }
        // Independent firing controls AFTER the scan use a fresh loader; pre-initializing the
        // scanned loader would mask an initialize=true regression.
        try (URLClassLoader control = loader(classes)) {
            Class<?> type = Class.forName("probe.SafetySubject", true, control);
            type.getDeclaredConstructor().newInstance();
            type.getDeclaredMethod("touch").invoke(null);
        }
        assertThat(markers.resolve("static")).exists();
        assertThat(markers.resolve("field")).exists();
        assertThat(markers.resolve("constructor")).exists();
        assertThat(markers.resolve("method")).exists();
        // The service-discovered processor is demonstrably runnable. This is explicitly a
        // preparation/control compiler invocation, never permission for the scanner to call it.
        JavaCompiler actual = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            assertThat(actual.getTask(null, manager, null, Collections.emptyList(), null,
                    manager.getJavaFileObjects(subject.toFile())).call()).isTrue();
        }
        assertThat(markers.resolve("processor")).exists();
        assertThat(output.resolve("probe/SafetySubject.class")).exists();
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Path compile(Path root, Path... source) throws IOException {
        Path output = Files.createDirectories(root.resolve("classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("fixture preparation requires the locked full JDK").isNotNull();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<java.io.File> files = Arrays.stream(source).map(Path::toFile).collect(Collectors.toList());
            assertThat(compiler.getTask(null, manager, null, Arrays.asList("-proc:none", "-d", output.toString()),
                    null, manager.getJavaFileObjectsFromFiles(files)).call()).as("fixture compilation, not scanning").isTrue();
        }
        return output;
    }

    private static URLClassLoader loader(Path classes) throws IOException {
        return new URLClassLoader(new URL[] {classes.toUri().toURL()}, JavadocDeprecationScannerTest.class.getClassLoader());
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.filter(Files::isRegularFile).sorted().collect(Collectors.toList())) {
                result.put(root.relativize(p).toString(), Files.getLastModifiedTime(p) + ":"
                        + Arrays.toString(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
            }
        }
        return result;
    }

    /** Installs a genuinely discoverable processor and observable class-output destination. */
    private static final class WatchingCompiler implements JavaCompiler {
        private final JavaCompiler delegate = ToolProvider.getSystemJavaCompiler();
        private final Path processors;
        private final Path output;
        private List<String> options;

        WatchingCompiler(Path processors, Path output) {
            this.processors = processors;
            this.output = output;
        }

        @Override
        public CompilationTask getTask(Writer out, JavaFileManager manager, DiagnosticListener<? super JavaFileObject> listener,
                Iterable<String> options, Iterable<String> classes, Iterable<? extends JavaFileObject> units) {
            this.options = new ArrayList<>();
            options.forEach(this.options::add);
            return delegate.getTask(out, manager, listener, options, classes, units);
        }

        @Override
        public StandardJavaFileManager getStandardFileManager(DiagnosticListener<? super JavaFileObject> listener,
                Locale locale, java.nio.charset.Charset charset) {
            StandardJavaFileManager manager = delegate.getStandardFileManager(listener, locale, charset);
            try {
                manager.setLocation(StandardLocation.ANNOTATION_PROCESSOR_PATH, Collections.singletonList(processors.toFile()));
                manager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(output.toFile()));
                manager.setLocation(StandardLocation.SOURCE_OUTPUT, Collections.singletonList(output.toFile()));
                return manager;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public int run(InputStream in, OutputStream out, OutputStream err, String... args) {
            return delegate.run(in, out, err, args);
        }

        @Override
        public Set<SourceVersion> getSourceVersions() {
            return delegate.getSourceVersions();
        }

        @Override
        public int isSupportedOption(String option) {
            return delegate.isSupportedOption(option);
        }
    }
}
