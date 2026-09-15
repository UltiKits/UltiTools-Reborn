package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Pins {@link OverBroadExclusionInvariant}'s #401 rule: a bare fully-qualified-name (whole-class)
 * japicmp {@code <exclude>} entry is legitimate only for a class that no longer exists in the build
 * output. Synthetic cases cover the discrimination logic; the "real" tests near the bottom run the
 * rule against the real {@code pom.xml} and the real, currently-derived set of existing classes,
 * which is what gates the build.
 *
 * <p><b>#414: class existence is derived from {@code src/main/java}, not from {@code
 * target/classes}.</b> A build-output directory can hold {@code .class} files left over from a
 * previous, non-{@code clean} build for a class that has since been deleted or renamed — {@code mvn
 * test} (not {@code mvn clean test}) after switching branches, or in a working tree that shares a
 * {@code target/} with an earlier state, can leave stale artefacts behind. Reading "does this class
 * exist" from that directory therefore makes the check's verdict depend on build history it cannot
 * see, which is exactly the defect class this milestone exists to close, applied to the build itself.
 *
 * <p>Two candidates were weighed for a source of truth that cannot go stale relative to itself:
 * deriving class names directly from {@code src/main/java}, or reading the compiler plugin's own
 * incremental-build bookkeeping under {@code target/maven-status/}. The source tree wins: it is the
 * one input that <em>is</em> "what currently exists" by definition (nothing to go stale against),
 * whereas the compiler's tracked-file lists name source files, not classes, and would still need the
 * same nested/inner-class name derivation this class already needs for the {@code $}-qualified form
 * japicmp's own exclusion keys use — reading `src/main/java` directly gets that derivation once,
 * without a second file format to trust.
 */
@DisplayName("OverBroadExclusionInvariant tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class OverBroadExclusionInvariantTest {

    /** Matches a top-level or nested type declaration's opening keyword and simple name. */
    private static final Pattern JAVA_TYPE_OPEN = Pattern.compile(
            "\\b(?:class|interface|enum|@\\s*interface)\\s+(\\w+)");
    private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    @Test
    @DisplayName("a class-level key whose class is present in the build output produces exactly one violation naming that class")
    void classLevelKeyPresentInBuildOutputViolates() {
        Set<RegistryKey> excludeKeys = setOf(RegistryKey.forClass("com.example.Survivor"));
        Set<String> classesInBuildOutput = setOf("com.example.Survivor");

        List<String> violations = OverBroadExclusionInvariant.evaluate(excludeKeys, classesInBuildOutput);

        assertThat(violations)
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("com.example.Survivor"));
    }

    @Test
    @DisplayName("a class-level key whose class is absent from the build output produces no violation - the class was genuinely removed")
    void classLevelKeyAbsentFromBuildOutputIsClean() {
        Set<RegistryKey> excludeKeys = setOf(RegistryKey.forClass("com.example.Deleted"));
        Set<String> classesInBuildOutput = setOf("com.example.Unrelated");

        List<String> violations = OverBroadExclusionInvariant.evaluate(excludeKeys, classesInBuildOutput);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("a member-level key produces no violation even when its class is present - member-level is the correct instrument")
    void memberLevelKeyNeverViolatesEvenWhenClassPresent() {
        Set<RegistryKey> excludeKeys = setOf(RegistryKey.forMember(
                "com.example.Survivor", "removedMethod", Collections.singletonList("java.lang.String")));
        Set<String> classesInBuildOutput = setOf("com.example.Survivor");

        List<String> violations = OverBroadExclusionInvariant.evaluate(excludeKeys, classesInBuildOutput);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("a field key produces no violation")
    void fieldKeyNeverViolates() {
        Set<RegistryKey> excludeKeys = setOf(RegistryKey.forField("com.example.Survivor", "removedField"));
        Set<String> classesInBuildOutput = setOf("com.example.Survivor");

        List<String> violations = OverBroadExclusionInvariant.evaluate(excludeKeys, classesInBuildOutput);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("a nested-class key is tested against the exact $-qualified string - the TempListener$PlayerTempListenerBuilder case")
    void nestedClassKeyIsMatchedExactlyNotByOuterClassAlone() {
        RegistryKey nestedKey = RegistryKey.forClass("com.x.Outer$Inner");

        // The outer class survives, the nested class does not - build output holds Outer but not Outer$Inner.
        List<String> whenOnlyOuterPresent = OverBroadExclusionInvariant.evaluate(
                setOf(nestedKey), setOf("com.x.Outer"));
        assertThat(whenOnlyOuterPresent)
                .as("presence of the outer class alone must not be treated as a match for the nested key")
                .isEmpty();

        // The nested class itself is still present in the build output - this is the over-broad case.
        List<String> whenNestedPresent = OverBroadExclusionInvariant.evaluate(
                setOf(nestedKey), setOf("com.x.Outer$Inner"));
        assertThat(whenNestedPresent)
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("com.x.Outer$Inner"));

        // Neither present - genuinely removed, clean.
        List<String> whenNeitherPresent = OverBroadExclusionInvariant.evaluate(
                setOf(nestedKey), setOf("com.x.Unrelated"));
        assertThat(whenNeitherPresent).isEmpty();
    }

    @Test
    @DisplayName("several violations come back in a deterministic order across repeated runs")
    void violationsAreDeterministicallyOrdered() {
        Set<RegistryKey> excludeKeys = setOf(
                RegistryKey.forClass("com.example.Zebra"),
                RegistryKey.forClass("com.example.Apple"),
                RegistryKey.forClass("com.example.Mango"));
        Set<String> classesInBuildOutput = setOf("com.example.Zebra", "com.example.Apple", "com.example.Mango");

        List<String> firstRun = OverBroadExclusionInvariant.evaluate(excludeKeys, classesInBuildOutput);
        List<String> secondRun = OverBroadExclusionInvariant.evaluate(excludeKeys, classesInBuildOutput);

        assertThat(firstRun).hasSize(3).isEqualTo(secondRun);
        assertThat(firstRun.get(0)).contains("com.example.Apple");
        assertThat(firstRun.get(1)).contains("com.example.Mango");
        assertThat(firstRun.get(2)).contains("com.example.Zebra");
    }

    @Test
    @DisplayName("an empty exclude set produces no violations")
    void emptyExcludeSetIsClean() {
        List<String> violations = OverBroadExclusionInvariant.evaluate(
                Collections.emptySet(), setOf("com.example.Anything"));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("#414 behaviour 1/6 (real): the real pom.xml against the real, source-derived class set produces zero violations")
    void realPomAgainstRealSourceTreeIsClean() throws IOException {
        Document pomDocument = DeprecationRegistryGenerator.readPomDocument();
        Set<RegistryKey> excludeKeys = DeprecationRegistryGenerator.readPomExcludeKeys(pomDocument);
        Set<String> classesInSource = scanSourceClasses();

        List<String> violations = OverBroadExclusionInvariant.evaluate(excludeKeys, classesInSource);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("#414 behaviour 5/6 (real): the real check fails closed rather than passing vacuously when the source tree yields no class name")
    void realCheckFailsClosedOnMissingOrEmptySourceTree() throws IOException {
        Set<String> classesInSource = scanSourceClasses();

        assertThat(classesInSource)
                .as("src/main/java must yield derived class names by the time this test runs - it is "
                        + "checked-in source, not a build artefact. An empty derivation here means "
                        + "something is structurally wrong (e.g. the wrong root was scanned), not that "
                        + "the check passed.")
                .isNotEmpty();
    }

    @Test
    @DisplayName("#414 behaviour 3/6: a nested class declared in source is recognised in the exact $-qualified form the exclusion keys use")
    void nestedClassInSourceTreeIsRecognisedInDollarQualifiedForm(@TempDir Path tempDir) throws IOException {
        Path srcRoot = tempDir.resolve("src/main/java");
        Path packageDir = srcRoot.resolve("com/example/nested");
        Files.createDirectories(packageDir);
        Files.write(packageDir.resolve("Outer.java"), (
                "package com.example.nested;\n"
                        + "public class Outer {\n"
                        + "    public static class Inner {\n"
                        + "    }\n"
                        + "}\n").getBytes(StandardCharsets.UTF_8));

        Set<String> classes = scanSourceClasses(srcRoot);

        assertThat(classes).contains("com.example.nested.Outer", "com.example.nested.Outer$Inner");
    }

    @Test
    @DisplayName("#414 behaviour 4/6: a stale target/classes artefact for a class genuinely absent from source does not cause a violation")
    void staleBuildArtefactForDeletedClassDoesNotViolate() throws IOException {
        String ghostClassName = "com.ultikits.ultitools.GhostClassThatDoesNotExistInSource";
        Path ghostClassFile = Paths.get("target", "classes",
                ghostClassName.replace('.', java.io.File.separatorChar) + ".class");
        Files.createDirectories(ghostClassFile.getParent());
        Files.write(ghostClassFile, new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        try {
            Set<RegistryKey> excludeKeys = setOf(RegistryKey.forClass(ghostClassName));
            Set<String> classesInSource = scanSourceClasses();

            assertThat(classesInSource)
                    .as("a fabricated stale .class file must never make it into the source-derived set")
                    .doesNotContain(ghostClassName);

            List<String> violations = OverBroadExclusionInvariant.evaluate(excludeKeys, classesInSource);
            assertThat(violations)
                    .as("a leftover build artefact for a class genuinely absent from source must not be "
                            + "treated as still-present")
                    .isEmpty();
        } finally {
            Files.deleteIfExists(ghostClassFile);
        }
    }

    @Test
    @DisplayName("#414 behaviour 6/6: the source-derived class set is identical whether or not a build ran immediately before")
    void sourceDerivationIsIndependentOfPriorBuildState() throws IOException {
        Set<String> beforeGhost = scanSourceClasses();

        Path ghostClassFile = Paths.get("target", "classes", "com", "ultikits", "ultitools",
                "AnotherGhostClassForBuildIndependence.class");
        Files.createDirectories(ghostClassFile.getParent());
        Files.write(ghostClassFile, new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        try {
            Set<String> afterGhost = scanSourceClasses();
            assertThat(afterGhost)
                    .as("the derived class set must not depend on what happens to be sitting in target/classes")
                    .isEqualTo(beforeGhost);
        } finally {
            Files.deleteIfExists(ghostClassFile);
        }
    }

    private static Set<RegistryKey> setOf(RegistryKey... keys) {
        return new LinkedHashSet<>(Arrays.asList(keys));
    }

    private static Set<String> setOf(String... classNames) {
        return new LinkedHashSet<>(Arrays.asList(classNames));
    }

    /**
     * Derives the set of fully-qualified class names currently declared under {@code src/main/java}
     * - #414's replacement for walking {@code target/classes}. Nested/inner classes are returned in
     * the same {@code Outer$Inner} form japicmp's own {@code <exclude>} keys use.
     */
    private static Set<String> scanSourceClasses() throws IOException {
        return scanSourceClasses(Paths.get("src", "main", "java"));
    }

    /**
     * Same derivation as {@link #scanSourceClasses()}, against an explicit root - so a synthetic,
     * temporary source tree can be scanned in a unit test without touching the real project.
     */
    private static Set<String> scanSourceClasses(Path srcRoot) throws IOException {
        Set<String> classNames = new LinkedHashSet<>();
        if (!Files.isDirectory(srcRoot)) {
            return classNames;
        }
        List<Path> javaFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(srcRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(javaFiles::add);
        }
        for (Path file : javaFiles) {
            classNames.addAll(classNamesDeclaredIn(file));
        }
        return classNames;
    }

    /**
     * Scans one {@code .java} file for every top-level and nested type declaration, tracking brace
     * depth over a comment/string-masked copy of the source (mirroring {@code
     * JavadocDeprecationScanner}'s established masking pattern in this same package) so a javadoc
     * {@code {@link}} or a string literal's braces are never mistaken for a type body's braces.
     */
    private static Set<String> classNamesDeclaredIn(Path file) throws IOException {
        String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        String packageName = extractPackageName(source);
        String masked = maskCommentsAndStrings(source);

        Set<String> names = new LinkedHashSet<>();
        List<String> stack = new ArrayList<>();
        List<Integer> depthStack = new ArrayList<>();
        int depth = 0;
        String pendingName = null;

        Matcher typeMatcher = JAVA_TYPE_OPEN.matcher(masked);
        int nextMatchStart = typeMatcher.find() ? typeMatcher.start() : -1;

        for (int i = 0; i < masked.length(); i++) {
            if (i == nextMatchStart) {
                pendingName = typeMatcher.group(1);
                nextMatchStart = typeMatcher.find() ? typeMatcher.start() : -1;
            }
            char c = masked.charAt(i);
            if (c == '{') {
                depth++;
                if (pendingName != null) {
                    stack.add(pendingName);
                    depthStack.add(depth);
                    names.add(buildFqn(packageName, stack));
                    pendingName = null;
                }
            } else if (c == '}') {
                if (!depthStack.isEmpty() && depthStack.get(depthStack.size() - 1) == depth) {
                    depthStack.remove(depthStack.size() - 1);
                    if (!stack.isEmpty()) {
                        stack.remove(stack.size() - 1);
                    }
                }
                depth--;
            }
        }
        return names;
    }

    private static String extractPackageName(String source) {
        Matcher m = PACKAGE_DECL.matcher(source);
        return m.find() ? m.group(1) : "";
    }

    private static String buildFqn(String packageName, List<String> classStack) {
        String chain = String.join("$", classStack);
        return packageName.isEmpty() ? chain : packageName + "." + chain;
    }

    /**
     * Replaces every character inside a {@code //} line comment, a {@code /* *}{@code /} block
     * comment (including javadoc), a string literal, or a char literal with a space, preserving
     * newlines and overall length - identical technique to {@code
     * JavadocDeprecationScanner#maskCommentsAndStrings}, duplicated here rather than shared because
     * that method is {@code private} and this test file's scope (#414/Task 1) is deliberately
     * limited to this one file.
     */
    private static String maskCommentsAndStrings(String source) {
        char[] chars = source.toCharArray();
        char[] masked = chars.clone();
        int n = chars.length;
        int i = 0;
        while (i < n) {
            char c = chars[i];
            if (c == '/' && i + 1 < n && chars[i + 1] == '/') {
                int start = i;
                while (i < n && chars[i] != '\n') {
                    i++;
                }
                blank(masked, start, i);
            } else if (c == '/' && i + 1 < n && chars[i + 1] == '*') {
                int start = i;
                i += 2;
                while (i + 1 < n && !(chars[i] == '*' && chars[i + 1] == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                blank(masked, start, i);
            } else if (c == '"') {
                int start = i;
                i++;
                while (i < n && chars[i] != '"') {
                    if (chars[i] == '\\' && i + 1 < n) {
                        i++;
                    }
                    i++;
                }
                i = Math.min(i + 1, n);
                blank(masked, start, i);
            } else if (c == '\'') {
                int start = i;
                i++;
                while (i < n && chars[i] != '\'') {
                    if (chars[i] == '\\' && i + 1 < n) {
                        i++;
                    }
                    i++;
                }
                i = Math.min(i + 1, n);
                blank(masked, start, i);
            } else {
                i++;
            }
        }
        return new String(masked);
    }

    private static void blank(char[] masked, int from, int to) {
        for (int j = from; j < to; j++) {
            if (masked[j] != '\n') {
                masked[j] = ' ';
            }
        }
    }
}
