package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
        for (Map.Entry<String, DeprecationEntry.Kind> row : expected.entrySet()) {
            List<DeprecationEntry> matches = entries.stream()
                    .filter(e -> e.getKey().toString().equals(row.getKey())).collect(Collectors.toList());
            assertThat(matches).as("exact signature %s", row.getKey()).hasSize(1);
            assertThat(matches.get(0).getKind()).isEqualTo(row.getValue());
        }
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
    void nestedGenericBoundRetainsItsDeclaringOwner(@TempDir Path root) throws Exception {
        Path source = write(root.resolve("source/probe/Lexical.java"),
                "package probe; import java.util.Date; public class Lexical<T extends Date> {"
                        + " public class Inner { public class Date {}"
                        + " @Deprecated public void bound(T value) {} } }");
        Path classes = compile(root, source);
        // The inner same-spelled class must not rewrite the outer type variable's erasure.
        try (URLClassLoader loader = loader(classes)) {
            List<DeprecationEntry> entries = new ArrayList<>();
            assertThatCode(() -> entries.addAll(
                    new JavadocDeprecationScanner(loader).scan(source.getParent())))
                    .as("bound names resolve in their declaring owner, not a nested consuming owner")
                    .doesNotThrowAnyException();
            assertThat(entries).extracting(e -> e.getKey().toString())
                    .containsExactly("probe.Lexical$Inner#bound(java.util.Date)");
        }
    }

    // Member types inherited from supertypes are in scope in a class body (JLS 8.5, 6.4.1). Every
    // fixture below is compiled by javac first, so the compiled parameter type is javac's own
    // resolution: a scanner that resolves the simple name differently finds no reflected match.

    @Test
    void memberTypeInheritedFromSuperclassResolves(@TempDir Path root) throws Exception {
        assertThat(scanFixture(root,
                "Parent", "package probe; public class Parent { public static class Token {} }",
                "Child", "package probe; public class Child extends Parent {"
                        + " @Deprecated public void oldApi(Token value) {} }"))
                .containsExactly("probe.Child#oldApi(probe.Parent$Token)");
    }

    @Test
    void memberTypeInheritedFromSuperinterfaceResolves(@TempDir Path root) throws Exception {
        assertThat(scanFixture(root,
                "Contract", "package probe; public interface Contract { class Token {} }",
                "Impl", "package probe; public class Impl implements Contract {"
                        + " @Deprecated public Token field; @Deprecated public void oldApi(Token value) {} }",
                "Sub", "package probe; public interface Sub extends Contract {"
                        + " @Deprecated void viaInterface(Token[] values); }"))
                .containsExactlyInAnyOrder("probe.Impl#field", "probe.Impl#oldApi(probe.Contract$Token)",
                        "probe.Sub#viaInterface(probe.Contract$Token[])");
    }

    @Test
    void memberTypeInheritedFromGrandparentResolves(@TempDir Path root) throws Exception {
        assertThat(scanFixture(root,
                "Marker", "package probe; public interface Marker { class Label {} }",
                "Grand", "package probe; public class Grand implements Marker { public static class Token {} }",
                "Parent", "package probe; public class Parent extends Grand {}",
                "Child", "package probe; public class Child extends Parent {"
                        + " @Deprecated public void oldApi(Token value, Label label) {}"
                        + " public static class Nested { @Deprecated public void nested(Token value) {} } }"))
                .containsExactlyInAnyOrder("probe.Child#oldApi(probe.Grand$Token,probe.Marker$Label)",
                        "probe.Child$Nested#nested(probe.Grand$Token)");
    }

    @Test
    void innermostScopeWinsAndDeclaredMemberTypesHideInheritedOnes(@TempDir Path root) throws Exception {
        // JLS 8.5: a declared member type hides a same-named inherited one; private member types are
        // not inherited. JLS 6.4.1: a type in scope in an inner class body (declared or inherited)
        // shadows same-named types of enclosing classes, single-type imports and the package.
        assertThat(scanFixture(root,
                "Parent", "package probe; public class Parent { public static class Token {}"
                        + " public static class Date {} private static class Hidden {} }",
                "Hidden", "package probe; public class Hidden {}",
                "Outer", "package probe; import java.util.Date; public class Outer { public static class Token {}"
                        + " public static class Child extends Parent {"
                        + " @Deprecated public void inheritedBeatsEnclosing(Token value) {}"
                        + " @Deprecated public void inheritedBeatsImport(Date value) {}"
                        + " @Deprecated public void privateIsNotInherited(Hidden value) {} }"
                        + " public static class Hider extends Parent { public static class Token {}"
                        + " @Deprecated public void declaredHidesInherited(Token value) {} } }",
                "Deep", "package probe; public class Deep extends Parent { public static class Mid {"
                        + " public static class Token {} public static class Inner {"
                        + " @Deprecated public void closerDeclaredBeatsFartherInherited(Token value) {} } } }"))
                .containsExactlyInAnyOrder("probe.Outer$Child#inheritedBeatsEnclosing(probe.Parent$Token)",
                        "probe.Outer$Child#inheritedBeatsImport(probe.Parent$Date)",
                        "probe.Outer$Child#privateIsNotInherited(probe.Hidden)",
                        "probe.Outer$Hider#declaredHidesInherited(probe.Outer$Hider$Token)",
                        "probe.Deep$Mid$Inner#closerDeclaredBeatsFartherInherited(probe.Deep$Mid$Token)");
    }

    @Test
    void ambiguousInheritedMemberTypeFailsClosed(@TempDir Path root) throws Exception {
        Path left = write(root.resolve("source/probe/Left.java"),
                "package probe; public interface Left { class Token {} }");
        Path right = write(root.resolve("source/probe/Right.java"),
                "package probe; public interface Right { class Token {} }");
        Path both = write(root.resolve("source/probe/Both.java"),
                "package probe; public class Both implements Left, Right {"
                        + " @Deprecated public void take(Left.Token value) {}"
                        + " @Deprecated public void take(Right.Token value) {} }");
        Path classes = compile(root, left, right, both);
        // Deliberately inconsistent source/bytecode: javac rejects the ambiguous simple name (JLS 8.5).
        write(both, "package probe; public class Both implements Left, Right {"
                + " @Deprecated public void take(Token value) {} }");
        try (URLClassLoader loader = loader(classes)) {
            assertThatThrownBy(() -> new JavadocDeprecationScanner(loader).scan(both.getParent()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Both.java:1")
                    .hasMessageContaining("Ambiguous match").hasMessageContaining("probe.Left$Token")
                    .hasMessageContaining("probe.Right$Token");
        }
    }

    // A qualified type name Q.Id names a member type of Q, and Q's member types include the ones
    // it inherits (JLS 6.5.5.2, 8.5). javac resolves Child.Token to Parent$Token; the scanner must
    // not only try the declared spelling Child$Token.

    @Test
    void qualifiedNameResolvesMemberTypeInheritedFromSuperclass(@TempDir Path root) throws Exception {
        assertThat(scanFixture(root,
                "Parent", "package probe; public class Parent { public static class Token {"
                        + " public static class Leaf {} } }",
                "Child", "package probe; public class Child extends Parent {"
                        + " @Deprecated public void oldApi(Child.Token value) {}"
                        + " @Deprecated public void packageQualified(probe.Child.Token[] values) {}"
                        + " @Deprecated public void throughInherited(Child.Token.Leaf leaf) {} }",
                "Other", "package probe; public class Other {"
                        + " @Deprecated public void viaSubclass(Child.Token value) {} }"))
                .containsExactlyInAnyOrder("probe.Child#oldApi(probe.Parent$Token)",
                        "probe.Child#packageQualified(probe.Parent$Token[])",
                        "probe.Child#throughInherited(probe.Parent$Token$Leaf)",
                        "probe.Other#viaSubclass(probe.Parent$Token)");
    }

    @Test
    void qualifiedNameResolvesMemberTypeInheritedFromInterface(@TempDir Path root) throws Exception {
        assertThat(scanFixture(root,
                "Contract", "package probe; public interface Contract { class Token {} }",
                "Impl", "package probe; public class Impl implements Contract {"
                        + " @Deprecated public Impl.Token field; @Deprecated public void oldApi(Impl.Token value) {} }",
                "Sub", "package probe; public interface Sub extends Contract {"
                        + " @Deprecated void viaInterface(Sub.Token[] values); }"))
                .containsExactlyInAnyOrder("probe.Impl#field", "probe.Impl#oldApi(probe.Contract$Token)",
                        "probe.Sub#viaInterface(probe.Contract$Token[])");
    }

    @Test
    void qualifiedDeclaredMemberTypeHidesInheritedOne(@TempDir Path root) throws Exception {
        // Each pair of overloads differs only in which Token it takes. If the qualified Hider.Token
        // also yielded the hidden Parent$Token, both overloads would match and the scan would fail.
        assertThat(scanFixture(root,
                "Parent", "package probe; public class Parent { public static class Token {} }",
                "Hider", "package probe; public class Hider extends Parent { public static class Token {}"
                        + " @Deprecated public void hides(Hider.Token value) {}"
                        + " @Deprecated public void hides(Parent.Token value) {} }",
                "Other", "package probe; public class Other {"
                        + " @Deprecated public void viaHider(Hider.Token value) {}"
                        + " @Deprecated public void viaHider(Parent.Token value) {} }"))
                .containsExactlyInAnyOrder("probe.Hider#hides(probe.Hider$Token)",
                        "probe.Hider#hides(probe.Parent$Token)", "probe.Other#viaHider(probe.Hider$Token)",
                        "probe.Other#viaHider(probe.Parent$Token)");
    }

    @Test
    void staticImportsResolveInheritedMemberTypes(@TempDir Path root) throws Exception {
        // A static import names any static member of the type, inherited ones included (JLS 7.5.3,
        // 7.5.4); javac compiles both forms below to Parent$Token.
        assertThat(scanFixture(root,
                "Parent", "package probe; public class Parent { public static class Token {} }",
                "Child", "package probe; public class Child extends Parent {}",
                "Single", "package probe; import static probe.Child.Token; public class Single {"
                        + " @Deprecated public void viaStaticImport(Token value) {} }",
                "OnDemand", "package probe; import static probe.Child.*; public class OnDemand {"
                        + " @Deprecated public void viaStaticOnDemand(Token value) {} }"))
                .containsExactlyInAnyOrder("probe.Single#viaStaticImport(probe.Parent$Token)",
                        "probe.OnDemand#viaStaticOnDemand(probe.Parent$Token)");
    }

    @Test
    void typeImportOnDemandDoesNotImportInheritedMemberTypes(@TempDir Path root) throws Exception {
        Path parent = write(root.resolve("source/probe/Parent.java"),
                "package probe; public class Parent { public static class Token {} }");
        Path child = write(root.resolve("source/probe/Child.java"),
                "package probe; public class Child extends Parent {}");
        Path user = write(root.resolve("source/probe/user/User.java"),
                "package probe.user; public class User { @Deprecated public void take(probe.Parent.Token value) {} }");
        Path classes = compile(root, parent, child, user);
        // Deliberately inconsistent source/bytecode: javac rejects this simple name, because a
        // type-import-on-demand imports only the member types the named type declares (JLS 7.5.2).
        write(user, "package probe.user; import probe.Child.*; public class User {"
                + " @Deprecated public void take(Token value) {} }");
        try (URLClassLoader loader = loader(classes)) {
            assertThatThrownBy(() -> new JavadocDeprecationScanner(loader).scan(user.getParent()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("User.java:1")
                    .hasMessageContaining("No reflected member matched");
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

    @Test
    void scanningNeverResolvesOtherAnnotationValuesOfDeprecatedDeclarations(@TempDir Path root) throws Exception {
        Path markers = Files.createDirectories(root.resolve("markers"));
        String marker = markers.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        Path subject = write(root.resolve("source/probe/EnumValueSubject.java"),
                "package probe; import java.lang.annotation.Retention; import java.lang.annotation.RetentionPolicy;"
                        + " @Deprecated @EnumValueSubject.Mark(EnumValueSubject.Mode.VALUE) public class EnumValueSubject {"
                        + " @Retention(RetentionPolicy.RUNTIME) public @interface Mark { Mode value(); }"
                        + " public enum Mode { VALUE; static { try { java.nio.file.Files.write(java.nio.file.Paths.get(\""
                        + marker + "\", \"enum\"), new byte[]{1}); } catch (java.io.IOException e) {"
                        + " throw new java.io.UncheckedIOException(e); } } }"
                        + " @Deprecated @Mark(Mode.VALUE) public String field;"
                        + " @Deprecated @Mark(Mode.VALUE) public EnumValueSubject() {}"
                        + " @Deprecated @Mark(Mode.VALUE) public void flagged() {} }");
        Path classes = compile(root, subject);
        try (URLClassLoader scanLoader = loader(classes)) {
            assertThat(new JavadocDeprecationScanner(scanLoader).scan(subject.getParent()))
                    .extracting(e -> e.getKey().toString()).containsExactly("probe.EnumValueSubject",
                            "probe.EnumValueSubject#field", "probe.EnumValueSubject#EnumValueSubject()",
                            "probe.EnumValueSubject#flagged()");
        }
        try (Stream<Path> files = Files.list(markers)) {
            assertThat(files.collect(Collectors.toList()))
                    .as("scanning must not initialize an enum named by a co-located annotation value").isEmpty();
        }
        // Firing control on a fresh loader: reflective annotation reads DO run the enum initializer,
        // so the empty marker directory above is evidence, not an inert sentinel.
        try (URLClassLoader control = loader(classes)) {
            assertThat(Class.forName("probe.EnumValueSubject", false, control).getDeclaredMethod("flagged")
                    .getAnnotations()).hasSize(2);
        }
        assertThat(markers.resolve("enum")).exists();
    }

    @Test
    void compileTimeConstantMetadataIsAccepted(@TempDir Path root) throws Exception {
        Path subject = write(root.resolve("source/probe/ConstantSubject.java"),
                "package probe; public class ConstantSubject { static final String VERSION = \"6.2.4\";"
                        + " @Deprecated(since = VERSION) public void constantSince() {}"
                        + " @Deprecated(since = Versions.NEXT, forRemoval = (true)) public void qualifiedConstant() {}"
                        + " @Deprecated(since = (\"6.2\" + \".6\"), forRemoval = (!false)) public void parenthesised() {} }"
                        + " class Versions { static final String NEXT = \"6.2.5\"; }");
        Path classes = compile(root, subject);
        try (URLClassLoader loader = loader(classes)) {
            List<DeprecationEntry> entries = new ArrayList<>();
            assertThatCode(() -> entries.addAll(
                    new JavadocDeprecationScanner(loader).scan(subject.getParent())))
                    .as("javac-accepted constant expressions must not fail registry generation")
                    .doesNotThrowAnyException();
            assertThat(entries).extracting(e -> e.getKey().toString(), DeprecationEntry::getSince,
                    DeprecationEntry::isForRemoval, DeprecationEntry::getStatus).containsExactly(
                    org.assertj.core.groups.Tuple.tuple("probe.ConstantSubject#constantSince()", "6.2.4", false,
                            DeprecationEntry.Status.DEPRECATED),
                    org.assertj.core.groups.Tuple.tuple("probe.ConstantSubject#qualifiedConstant()", "6.2.5", true,
                            DeprecationEntry.Status.ANNOUNCED),
                    org.assertj.core.groups.Tuple.tuple("probe.ConstantSubject#parenthesised()", "6.2.6", true,
                            DeprecationEntry.Status.ANNOUNCED));
        }
    }

    /** Writes {@code probe} sources given as name/content pairs, compiles them, then scans them. */
    private static List<String> scanFixture(Path root, String... namesAndSources) throws Exception {
        Path[] sources = new Path[namesAndSources.length / 2];
        for (int i = 0; i < sources.length; i++) {
            sources[i] = write(root.resolve("source/probe/" + namesAndSources[2 * i] + ".java"),
                    namesAndSources[2 * i + 1]);
        }
        Path classes = compile(root, sources);
        try (URLClassLoader loader = loader(classes)) {
            List<DeprecationEntry> entries = new ArrayList<>();
            assertThatCode(() -> entries.addAll(
                    new JavadocDeprecationScanner(loader).scan(sources[0].getParent())))
                    .as("type names must resolve the way javac resolved them")
                    .doesNotThrowAnyException();
            return entries.stream().map(e -> e.getKey().toString()).collect(Collectors.toList());
        }
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
