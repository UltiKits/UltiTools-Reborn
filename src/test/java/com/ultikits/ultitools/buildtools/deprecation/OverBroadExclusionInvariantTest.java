package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Pins {@link OverBroadExclusionInvariant}'s #401 rule: a bare fully-qualified-name (whole-class)
 * japicmp {@code <exclude>} entry is legitimate only for a class that no longer exists in the build
 * output. Synthetic cases cover the discrimination logic; the "real" tests near the bottom run the
 * rule against the real {@code pom.xml} and the real, currently-derived set of existing classes,
 * which is what gates the build.
 *
 * <p><b>#414 / CR-01: existence is derived from {@code target/classes}' own file existence and
 * per-compilation-unit freshness, NOT from parsing {@code src/main/java} as text.</b> The first
 * attempt at #414 (a regex/brace-depth text scan of {@code .java} source) traded one blind spot for
 * another: it eliminated staleness against a leftover build (the original bug) but became
 * permanently unable to see any annotation-processor-generated type - concretely, every Lombok
 * {@code @Builder}'s generated {@code *Builder} nested class in this codebase never appears as
 * {@code class X {} } (or any form a text scan looks for) anywhere in source, so a class-level
 * exclude naming one would never be flagged over-broad even while the class is very much alive
 * (review CR-01). This revision keeps {@code target/classes} - the only place a synthesized type is
 * visible at all - as the base set, and closes the ORIGINAL #414 hazard with two per-class checks
 * instead of a source-text parse:
 * <ol>
 *   <li><b>Top-level source existence.</b> A compiled class's simple name up to (but not including)
 *       its first {@code $} names the compilation unit; if {@code <TopLevelName>.java} no longer
 *       exists under {@code src/main/java}, the whole unit - including every nested class compiled
 *       from it - is gone, regardless of what {@code target/classes} still holds. This alone closes
 *       the original #414 scenario (a deleted or renamed top-level class leaving stale {@code
 *       .class} files behind).</li>
 *   <li><b>Same-compilation-unit freshness.</b> javac (re)writes every class declared in one source
 *       file together, in one compile invocation, so a nested class's {@code .class} file and its
 *       enclosing top-level class's {@code .class} file share a compile "epoch" - their last-modified
 *       timestamps land within a small window of each other. A STALE nested class - left over
 *       because a nested type was deleted from an otherwise still-existing, still-compiling file -
 *       was written by a much EARLIER compile than the top-level class's own most recent one, so its
 *       timestamp sits far outside that window. See {@link #STALE_TOLERANCE_MILLIS}'s javadoc for
     *   why a symmetric tolerance window is used instead of a strict "not older than" ordering.</li>
 * </ol>
 *
 * <p>The read_first task note's other candidate - reading the compiler plugin's own {@code
 * target/maven-status/} incremental-build bookkeeping - was reconsidered and rejected here too: that
 * bookkeeping records which SOURCE FILES were compiled, not which classes a Lombok/annotation
 * processor synthesized from them, so it would need exactly the same top-level-name convention this
 * class already applies directly to {@code target/classes} itself, with no accuracy gain and a
 * second, less-documented file format to trust.
 */
@DisplayName("OverBroadExclusionInvariant tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class OverBroadExclusionInvariantTest {

    /**
     * How close two class files' last-modified timestamps must be to be treated as "written by the
     * same compile." NOT a strict "nested must not be older than top-level" ordering -
     * <b>measured</b> against this repository's own real, freshly-compiled Lombok {@code @Builder}
     * classes (four real pairs: {@code CommandContext}, {@code TabCompletionContext}, {@code
     * WhereCondition}, {@code ServerEntityVO}), every single nested {@code *Builder.class} file's
     * timestamp was consistently exactly 1ms <em>older</em> than its own outer class's {@code
     * .class} file, not newer or equal - javac does not guarantee any particular relative write
     * order across the class files it emits for one compilation unit, only that they land close
     * together in wall-clock time. A strict ordering check would therefore misclassify every real,
     * freshly-compiled Lombok builder in this codebase as "stale" - reintroducing a blind spot
     * exactly like the one this class exists to close, just via a different mechanism. A symmetric
     * tolerance window avoids that: same-compile-epoch class files land within single-digit
     * milliseconds of each other regardless of direction, while a genuinely stale leftover (from a
     * compile that ran before the nested type was deleted, while its still-existing enclosing file
     * was compiled again afterward) is separated from its top-level class's fresh timestamp by
     * whatever realistic gap exists between two separate developer edit/compile cycles - at minimum
     * seconds, ordinarily much more. 60 seconds is comfortably above the measured same-compile skew
     * (single-digit milliseconds) and comfortably below any plausible two-separate-builds gap.
     */
    static final long STALE_TOLERANCE_MILLIS = 60_000L;

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
    @DisplayName("#414 behaviour (real): the real pom.xml against the real, existence+freshness-derived class set produces zero violations")
    void realPomAgainstRealBuildOutputIsClean() throws IOException {
        Document pomDocument = DeprecationRegistryGenerator.readPomDocument();
        Set<RegistryKey> excludeKeys = DeprecationRegistryGenerator.readPomExcludeKeys(pomDocument);
        Set<String> classesInBuildOutput = scanExistingClasses();

        List<String> violations = OverBroadExclusionInvariant.evaluate(excludeKeys, classesInBuildOutput);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("#414 behaviour (real): the real check fails closed rather than passing vacuously when target/classes is missing or empty")
    void realCheckFailsClosedOnMissingOrEmptyBuildOutput() throws IOException {
        Set<String> classesInBuildOutput = scanExistingClasses();

        assertThat(classesInBuildOutput)
                .as("target/classes must contain compiled classes by the time this test runs - "
                        + "`compile` runs before `test` in the default Maven lifecycle. An empty scan "
                        + "here means something is structurally wrong (e.g. target/classes was cleaned "
                        + "or moved after compile), not that the check passed.")
                .isNotEmpty();
    }

    @Test
    @DisplayName("CR-01 (real): every real Lombok @Builder nested class in this repository is recognised as existing")
    void realLombokBuilderClassesAreRecognisedAsExisting() throws IOException {
        Set<String> classesInBuildOutput = scanExistingClasses();

        assertThat(classesInBuildOutput).contains(
                "com.ultikits.ultitools.abstracts.command.CommandContext$CommandContextBuilder",
                "com.ultikits.ultitools.commands.tabcomplete.TabCompletionContext$TabCompletionContextBuilder",
                "com.ultikits.ultitools.entities.WhereCondition$WhereConditionBuilder",
                "com.ultikits.ultitools.entities.vo.ServerEntityVO$ServerEntityVOBuilder");
    }

    @Test
    @DisplayName("CR-01 (real, regression pin): a class-level exclude naming a real, still-existing Lombok @Builder "
            + "nested class IS flagged over-broad - the exact scenario the review found latent")
    void classLevelExcludeOnRealLombokBuilderIsFlaggedOverBroad() throws IOException {
        Set<String> classesInBuildOutput = scanExistingClasses();
        Set<RegistryKey> excludeKeys = setOf(
                RegistryKey.forClass("com.ultikits.ultitools.entities.WhereCondition$WhereConditionBuilder"));

        List<String> violations = OverBroadExclusionInvariant.evaluate(excludeKeys, classesInBuildOutput);

        assertThat(violations)
                .as("WhereCondition$WhereConditionBuilder is real, compiled, and alive - excluding it "
                        + "whole-class must be flagged, not silently pass")
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("WhereCondition$WhereConditionBuilder"));
    }

    @Test
    @DisplayName("a Lombok-@Builder-shaped generated nested class (fixture) is recognised as existing")
    void lombokBuilderShapedFixtureIsRecognisedAsExisting(@TempDir Path tempDir) throws IOException {
        Path srcRoot = tempDir.resolve("src/main/java");
        Path classesRoot = tempDir.resolve("target/classes");
        Path pkg = Paths.get("com", "example", "lombok");
        Files.createDirectories(srcRoot.resolve(pkg));
        Files.createDirectories(classesRoot.resolve(pkg));

        // The source file exists and is annotated @Builder, but the Builder class itself never
        // appears as text anywhere in it - exactly like the real Lombok-generated case.
        Files.write(srcRoot.resolve(pkg).resolve("Widget.java"), (
                "package com.example.lombok;\n"
                        + "@lombok.Builder\n"
                        + "public class Widget {\n"
                        + "    private final String name;\n"
                        + "}\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Both class files "compiled together" - same instant.
        Path outerClass = classesRoot.resolve(pkg).resolve("Widget.class");
        Path builderClass = classesRoot.resolve(pkg).resolve("Widget$WidgetBuilder.class");
        Files.write(outerClass, new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        Files.write(builderClass, new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        FileTime now = FileTime.fromMillis(System.currentTimeMillis());
        Files.setLastModifiedTime(outerClass, now);
        // 1ms earlier, matching this repository's own measured real-world skew direction.
        Files.setLastModifiedTime(builderClass, FileTime.fromMillis(now.toMillis() - 1));

        Set<String> classes = scanExistingClasses(srcRoot, classesRoot);

        assertThat(classes).contains(
                "com.example.lombok.Widget",
                "com.example.lombok.Widget$WidgetBuilder");
    }

    @Test
    @DisplayName("a ghost class whose top-level source is gone is not recognised as existing, even though its .class file is still present")
    void ghostClassWithNoTopLevelSourceIsNotRecognised(@TempDir Path tempDir) throws IOException {
        Path srcRoot = tempDir.resolve("src/main/java");
        Path classesRoot = tempDir.resolve("target/classes");
        Path pkg = Paths.get("com", "example", "ghost");
        Files.createDirectories(srcRoot.resolve(pkg));
        Files.createDirectories(classesRoot.resolve(pkg));
        // No Ghost.java is ever written - only the compiled artefact survives, as if the source
        // file had been deleted after an earlier build and target/classes was never cleaned.
        Files.write(classesRoot.resolve(pkg).resolve("Ghost.class"), new byte[] {0});

        Set<String> classes = scanExistingClasses(srcRoot, classesRoot);

        assertThat(classes).doesNotContain("com.example.ghost.Ghost");
    }

    @Test
    @DisplayName("a stale nested class, left over after being deleted from a source file that still exists and still compiles, is not recognised")
    void staleNestedClassOlderThanItsToplevelIsNotRecognised(@TempDir Path tempDir) throws IOException {
        Path srcRoot = tempDir.resolve("src/main/java");
        Path classesRoot = tempDir.resolve("target/classes");
        Path pkg = Paths.get("com", "example", "shrinking");
        Files.createDirectories(srcRoot.resolve(pkg));
        Files.createDirectories(classesRoot.resolve(pkg));

        // Outer.java still exists (and still compiles) but no longer declares Inner - exactly the
        // scenario a pure top-level-source-existence check cannot catch on its own.
        Files.write(srcRoot.resolve(pkg).resolve("Outer.java"), (
                "package com.example.shrinking;\n"
                        + "public class Outer {\n"
                        + "}\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Path outerClass = classesRoot.resolve(pkg).resolve("Outer.class");
        Path staleInnerClass = classesRoot.resolve(pkg).resolve("Outer$Inner.class");
        Files.write(outerClass, new byte[] {0});
        Files.write(staleInnerClass, new byte[] {0});

        // Outer.class was just rewritten by the recompile that dropped Inner; Outer$Inner.class is
        // a leftover from a much earlier compile - comfortably outside the tolerance window.
        FileTime freshCompile = FileTime.fromMillis(System.currentTimeMillis());
        FileTime staleCompile = FileTime.fromMillis(freshCompile.toMillis() - (STALE_TOLERANCE_MILLIS * 10));
        Files.setLastModifiedTime(outerClass, freshCompile);
        Files.setLastModifiedTime(staleInnerClass, staleCompile);

        Set<String> classes = scanExistingClasses(srcRoot, classesRoot);

        assertThat(classes)
                .contains("com.example.shrinking.Outer")
                .doesNotContain("com.example.shrinking.Outer$Inner");
    }

    @Test
    @DisplayName("nested/inner class naming in the derived set matches the exact $-qualified exclusion-key form")
    void nestedClassNamingMatchesExclusionKeyForm(@TempDir Path tempDir) throws IOException {
        Path srcRoot = tempDir.resolve("src/main/java");
        Path classesRoot = tempDir.resolve("target/classes");
        Path pkg = Paths.get("com", "example", "nested");
        Files.createDirectories(srcRoot.resolve(pkg));
        Files.createDirectories(classesRoot.resolve(pkg));
        Files.write(srcRoot.resolve(pkg).resolve("Outer.java"), (
                "package com.example.nested;\n"
                        + "public class Outer {\n"
                        + "    public static class Inner {\n"
                        + "    }\n"
                        + "}\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        FileTime now = FileTime.fromMillis(System.currentTimeMillis());
        Path outerClass = classesRoot.resolve(pkg).resolve("Outer.class");
        Path innerClass = classesRoot.resolve(pkg).resolve("Outer$Inner.class");
        Files.write(outerClass, new byte[] {0});
        Files.write(innerClass, new byte[] {0});
        Files.setLastModifiedTime(outerClass, now);
        Files.setLastModifiedTime(innerClass, now);

        Set<String> classes = scanExistingClasses(srcRoot, classesRoot);

        assertThat(classes).contains("com.example.nested.Outer", "com.example.nested.Outer$Inner");
    }

    @Test
    @DisplayName("the derived class set is identical whether or not a build ran immediately before - a fabricated stale artefact for a "
            + "class genuinely absent from source does not change the verdict")
    void buildOrderIndependenceIsPreserved() throws IOException {
        Set<String> beforeGhost = scanExistingClasses();

        String ghostClassName = "com.ultikits.ultitools.GhostClassThatDoesNotExistInSource";
        Path ghostClassFile = Paths.get("target", "classes",
                ghostClassName.replace('.', java.io.File.separatorChar) + ".class");
        Files.createDirectories(ghostClassFile.getParent());
        Files.write(ghostClassFile, new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        try {
            Set<String> afterGhost = scanExistingClasses();
            assertThat(afterGhost)
                    .as("the derived class set must not depend on what happens to be sitting in target/classes "
                            + "for a class with no top-level source at all")
                    .isEqualTo(beforeGhost);
            assertThat(afterGhost).doesNotContain(ghostClassName);

            Set<RegistryKey> excludeKeys = setOf(RegistryKey.forClass(ghostClassName));
            List<String> violations = OverBroadExclusionInvariant.evaluate(excludeKeys, afterGhost);
            assertThat(violations)
                    .as("a leftover build artefact for a class genuinely absent from source must not be "
                            + "treated as still-present")
                    .isEmpty();
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
     * Derives the set of classes that "exist" for #414/CR-01's purposes: present in {@code
     * target/classes}, with a top-level source file that still exists, and (for a nested/inner
     * class) written in the same compile pass as its top-level class - see the class javadoc.
     */
    private static Set<String> scanExistingClasses() throws IOException {
        return scanExistingClasses(Paths.get("src", "main", "java"), Paths.get("target", "classes"));
    }

    /**
     * Same derivation as {@link #scanExistingClasses()}, against explicit roots - so a synthetic,
     * temporary project tree can be scanned in a unit test without touching the real project.
     */
    private static Set<String> scanExistingClasses(Path srcRoot, Path classesRoot) throws IOException {
        // RED (CR-01, intentionally the pre-fix behaviour): still a pure text scan of srcRoot,
        // ignoring classesRoot and the existence/freshness check entirely - proves the new
        // Lombok-@Builder-shaped tests below fail for the right reason (a real assertion, not a
        // compile error) before the GREEN commit swaps this body for the real algorithm.
        Set<String> classNames = new LinkedHashSet<>();
        if (!Files.isDirectory(srcRoot)) {
            return classNames;
        }
        java.util.List<Path> javaFiles = new java.util.ArrayList<>();
        try (Stream<Path> paths = Files.walk(srcRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(javaFiles::add);
        }
        java.util.regex.Pattern typeOpen = java.util.regex.Pattern.compile(
                "\\b(?:class|interface|enum|@\\s*interface)\\s+(\\w+)");
        java.util.regex.Pattern packageDecl = java.util.regex.Pattern.compile(
                "(?m)^\\s*package\\s+([\\w.]+)\\s*;");
        for (Path file : javaFiles) {
            String source = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher pkgMatcher = packageDecl.matcher(source);
            String packageName = pkgMatcher.find() ? pkgMatcher.group(1) : "";
            java.util.regex.Matcher typeMatcher = typeOpen.matcher(source);
            java.util.List<String> stack = new java.util.ArrayList<>();
            java.util.List<Integer> depthStack = new java.util.ArrayList<>();
            int depth = 0;
            String pendingName = null;
            int nextMatchStart = typeMatcher.find() ? typeMatcher.start() : -1;
            for (int i = 0; i < source.length(); i++) {
                if (i == nextMatchStart) {
                    pendingName = typeMatcher.group(1);
                    nextMatchStart = typeMatcher.find() ? typeMatcher.start() : -1;
                }
                char c = source.charAt(i);
                if (c == '{') {
                    depth++;
                    if (pendingName != null) {
                        stack.add(pendingName);
                        depthStack.add(depth);
                        String chain = String.join("$", stack);
                        classNames.add(packageName.isEmpty() ? chain : packageName + "." + chain);
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
        }
        return classNames;
    }

    private static String topLevelOf(String fqcn) {
        int dollar = fqcn.indexOf('$');
        return dollar < 0 ? fqcn : fqcn.substring(0, dollar);
    }
}
