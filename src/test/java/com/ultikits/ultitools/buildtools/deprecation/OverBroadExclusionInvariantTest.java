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
 * per-class freshness against its own source file, NOT from parsing {@code src/main/java} as
 * text.</b> The first attempt at #414 (a regex/brace-depth text scan of {@code .java} source)
 * traded one blind spot for another: it eliminated staleness against a leftover build (the
 * original bug) but became permanently unable to see any annotation-processor-generated type -
 * concretely, every Lombok {@code @Builder}'s generated {@code *Builder} nested class in this
 * codebase never appears as {@code class X {} } (or any form a text scan looks for) anywhere in
 * source, so a class-level exclude naming one would never be flagged over-broad even while the
 * class is very much alive (review CR-01). This revision keeps {@code target/classes} - the only
 * place a synthesized type is visible at all - as the base set, and closes the ORIGINAL #414
 * hazard with two per-class checks instead of a source-text parse:
 * <ol>
 *   <li><b>Top-level source existence.</b> A compiled class's simple name up to (but not including)
 *       its first {@code $} names the compilation unit; if {@code <TopLevelName>.java} no longer
 *       exists under {@code src/main/java}, the whole unit - including every nested class compiled
 *       from it - is gone, regardless of what {@code target/classes} still holds. This alone closes
 *       the original #414 scenario (a deleted or renamed top-level class leaving stale {@code
 *       .class} files behind).</li>
 *   <li><b>Freshness against the source file, not against a sibling class file.</b> A compiled
 *       class's {@code .class} file can only have been written by a compile that ran chronologically
 *       AFTER its top-level source file was last saved in its current form - a compiler cannot
 *       compile content that has not been written yet. So: keep a class only if its own {@code
 *       .class} file's last-modified time is {@code >=} its top-level {@code .java} file's
 *       last-modified time. A STALE nested class - left over because a nested type was deleted from
 *       an otherwise still-existing, still-compiling file - was written by a compile that predates
 *       the file's later edit (the one that removed the nested type); the source file's own
 *       last-modified time then moves past it on that later save, so the stale class's timestamp
 *       falls behind the source's CURRENT timestamp regardless of how soon afterward the file is
 *       recompiled. An earlier revision of this check compared a nested class's timestamp against
 *       its top-level class's own {@code .class} file (a tolerance window, "close enough to have
 *       plausibly been written by the same compile") - reviewed and replaced: measured 1ms of
 *       same-compile skew is not distinguishable, on timing alone, from a stale artefact left by
 *       recompiling the SAME outer file within that same window after deleting the nested type (a
 *       genuinely supported case - non-clean {@code mvn test} immediately after an edit). Comparing
 *       against the source file's own timestamp has no such race: it is not a "close enough" window
 *       at all, only a direction, and that direction is enforced by causality (compile output cannot
 *       predate the input it was compiled from), not by how much wall-clock time elapsed.</li>
 * </ol>
 *
 * <p><b>Tie-breaking, and why timestamps are compared at native precision, not truncated to
 * milliseconds.</b> A second external-review round (Codex, PR #480) correctly noted that comparing
 * timestamps truncated with {@code FileTime.toMillis()} manufactures collisions a filesystem's own,
 * usually finer, clock resolution would not produce - two genuinely distinct real timestamps (a
 * stale compile, and a later source save) can round to the identical millisecond value even when
 * the underlying filesystem resolves time far more finely (measured on this repository's own
 * filesystem: sub-millisecond, effectively microsecond-scale, resolution). {@link
 * java.nio.file.attribute.FileTime#compareTo} is used directly instead, at whatever precision
 * {@link Files#getLastModifiedTime} actually reports, removing that self-inflicted truncation
 * entirely. A genuine tie AT that native precision - the class's {@code .class} file and its
 * top-level source resolving to the exact same instant - is resolved toward KEEP, not DROP:
 * dropping on a tie would risk re-triggering CR-01's own failure mode (a real, current class
 * silently vanishing from the derived set) on any filesystem or CI environment coarse enough to
 * alias a save and its own immediate compile, which is a more likely and more damaging occurrence
 * than the reverse - a stale artefact's write time landing in the exact same instant as a LATER,
 * unrelated source edit is the far rarer coincidence of the two, and only rarer still once
 * truncation is removed.</p>
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
        Path widgetJava = srcRoot.resolve(pkg).resolve("Widget.java");
        Files.write(widgetJava, (
                "package com.example.lombok;\n"
                        + "@lombok.Builder\n"
                        + "public class Widget {\n"
                        + "    private final String name;\n"
                        + "}\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long sourceSaveMillis = System.currentTimeMillis();
        Files.setLastModifiedTime(widgetJava, FileTime.fromMillis(sourceSaveMillis));

        // Both class files compiled after the source was saved - real compiles always are.
        Path outerClass = classesRoot.resolve(pkg).resolve("Widget.class");
        Path builderClass = classesRoot.resolve(pkg).resolve("Widget$WidgetBuilder.class");
        Files.write(outerClass, new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        Files.write(builderClass, new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        Files.setLastModifiedTime(outerClass, FileTime.fromMillis(sourceSaveMillis + 1000));
        // 1ms behind the outer class - matching this repository's own measured real-world skew
        // direction between sibling class files of one compile - but still comfortably AFTER the
        // source file's save, which is the only comparison this check makes now.
        Files.setLastModifiedTime(builderClass, FileTime.fromMillis(sourceSaveMillis + 999));

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
    @DisplayName("a stale nested class, left over after being deleted from a source file that still exists and still "
            + "compiles, is not recognised - even when it is recompiled within milliseconds of the stale artefact "
            + "(the external-review regression: a same-compile TOLERANCE WINDOW would have missed this)")
    void staleNestedClassPredatingItsSourcesCurrentSaveIsNotRecognised(@TempDir Path tempDir) throws IOException {
        Path srcRoot = tempDir.resolve("src/main/java");
        Path classesRoot = tempDir.resolve("target/classes");
        Path pkg = Paths.get("com", "example", "shrinking");
        Files.createDirectories(srcRoot.resolve(pkg));
        Files.createDirectories(classesRoot.resolve(pkg));

        // Outer.java still exists (and still compiles) but no longer declares Inner - exactly the
        // scenario a pure top-level-source-existence check cannot catch on its own. Its
        // last-modified time marks the moment Inner was removed and the file resaved.
        Path outerJava = srcRoot.resolve(pkg).resolve("Outer.java");
        Files.write(outerJava, (
                "package com.example.shrinking;\n"
                        + "public class Outer {\n"
                        + "}\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long sourceSaveMillis = System.currentTimeMillis();
        Files.setLastModifiedTime(outerJava, FileTime.fromMillis(sourceSaveMillis));

        Path outerClass = classesRoot.resolve(pkg).resolve("Outer.class");
        Path staleInnerClass = classesRoot.resolve(pkg).resolve("Outer$Inner.class");
        Files.write(outerClass, new byte[] {0});
        Files.write(staleInnerClass, new byte[] {0});

        // Deliberately adversarial timing, not a comfortable margin: the recompile that dropped
        // Inner happens only 100ms after the save, and the stale Outer$Inner.class is from a
        // compile only 100ms BEFORE the save - both class files land within 200ms of each other,
        // comfortably inside what a same-compile-file tolerance window (the earlier revision of
        // this check) would have treated as "close enough to be the same compile," which is
        // exactly the false-negative the external review found. This check does not compare the
        // two class files against each other at all - only each one against the source's own
        // current timestamp - so the stale one is still correctly dropped regardless of how soon
        // afterward the recompile happened.
        Files.setLastModifiedTime(outerClass, FileTime.fromMillis(sourceSaveMillis + 100));
        Files.setLastModifiedTime(staleInnerClass, FileTime.fromMillis(sourceSaveMillis - 100));

        Set<String> classes = scanExistingClasses(srcRoot, classesRoot);

        assertThat(classes)
                .contains("com.example.shrinking.Outer")
                .doesNotContain("com.example.shrinking.Outer$Inner");
    }

    @Test
    @DisplayName("CR-01/Codex round 2: a class whose .class file's timestamp exactly ties its top-level source "
            + "file's timestamp (at the precision this check compares - no millisecond truncation) is kept, not "
            + "dropped - a documented tie-break, not an accident of rounding")
    void exactTimestampTieIsResolvedTowardKeepingTheClass(@TempDir Path tempDir) throws IOException {
        Path srcRoot = tempDir.resolve("src/main/java");
        Path classesRoot = tempDir.resolve("target/classes");
        Path pkg = Paths.get("com", "example", "tie");
        Files.createDirectories(srcRoot.resolve(pkg));
        Files.createDirectories(classesRoot.resolve(pkg));

        Path widgetJava = srcRoot.resolve(pkg).resolve("Widget.java");
        Files.write(widgetJava, (
                "package com.example.tie;\n"
                        + "public class Widget {\n"
                        + "}\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        FileTime tiedInstant = FileTime.fromMillis(System.currentTimeMillis());
        Files.setLastModifiedTime(widgetJava, tiedInstant);

        Path widgetClass = classesRoot.resolve(pkg).resolve("Widget.class");
        Files.write(widgetClass, new byte[] {0});
        // Deliberately the EXACT same FileTime value as the source file - not "close", identical.
        Files.setLastModifiedTime(widgetClass, tiedInstant);

        Set<String> classes = scanExistingClasses(srcRoot, classesRoot);

        assertThat(classes)
                .as("an exact timestamp tie must not be treated as staleness - see the class javadoc's "
                        + "tie-breaking rationale")
                .contains("com.example.tie.Widget");
    }

    @Test
    @DisplayName("nested/inner class naming in the derived set matches the exact $-qualified exclusion-key form")
    void nestedClassNamingMatchesExclusionKeyForm(@TempDir Path tempDir) throws IOException {
        Path srcRoot = tempDir.resolve("src/main/java");
        Path classesRoot = tempDir.resolve("target/classes");
        Path pkg = Paths.get("com", "example", "nested");
        Files.createDirectories(srcRoot.resolve(pkg));
        Files.createDirectories(classesRoot.resolve(pkg));
        Path outerJava = srcRoot.resolve(pkg).resolve("Outer.java");
        Files.write(outerJava, (
                "package com.example.nested;\n"
                        + "public class Outer {\n"
                        + "    public static class Inner {\n"
                        + "    }\n"
                        + "}\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long sourceSaveMillis = System.currentTimeMillis();
        Files.setLastModifiedTime(outerJava, FileTime.fromMillis(sourceSaveMillis));
        Path outerClass = classesRoot.resolve(pkg).resolve("Outer.class");
        Path innerClass = classesRoot.resolve(pkg).resolve("Outer$Inner.class");
        Files.write(outerClass, new byte[] {0});
        Files.write(innerClass, new byte[] {0});
        Files.setLastModifiedTime(outerClass, FileTime.fromMillis(sourceSaveMillis + 100));
        Files.setLastModifiedTime(innerClass, FileTime.fromMillis(sourceSaveMillis + 100));

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
        Set<String> result = new LinkedHashSet<>();
        if (!Files.isDirectory(classesRoot)) {
            return result;
        }

        // FileTime, not a millisecond long: Files.getLastModifiedTime already carries whatever
        // native precision the filesystem reports (commonly sub-millisecond, even nanosecond, on
        // ext4/APFS/NTFS) - truncating to milliseconds before comparing throws that precision away
        // for free and manufactures exactly the kind of "two real, distinct timestamps alias to
        // the same rounded value" collision an external review (Codex, PR #480 round 2) flagged as
        // a concrete risk. Comparing FileTime.compareTo() directly uses the filesystem's own
        // resolution instead of an artificial one this class would otherwise impose.
        Map<String, FileTime> classMtimes = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(classesRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        String relative = classesRoot.relativize(p).toString();
                        String withoutSuffix = relative.substring(0, relative.length() - ".class".length());
                        String fqcn = withoutSuffix.replace(java.io.File.separatorChar, '.');
                        try {
                            classMtimes.put(fqcn, Files.getLastModifiedTime(p));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }

        // Cache per top-level source file so a compilation unit with many nested classes only
        // stats its own .java file once.
        Map<String, FileTime> sourceMtimesByTopLevel = new LinkedHashMap<>();

        for (Map.Entry<String, FileTime> entry : classMtimes.entrySet()) {
            String fqcn = entry.getKey();
            String topLevelFqcn = topLevelOf(fqcn);

            FileTime sourceMtime = sourceMtimesByTopLevel.get(topLevelFqcn);
            if (sourceMtime == null && !sourceMtimesByTopLevel.containsKey(topLevelFqcn)) {
                Path sourceCandidate = srcRoot.resolve(
                        topLevelFqcn.replace('.', java.io.File.separatorChar) + ".java");
                if (Files.isRegularFile(sourceCandidate)) {
                    sourceMtime = Files.getLastModifiedTime(sourceCandidate);
                }
                sourceMtimesByTopLevel.put(topLevelFqcn, sourceMtime);
            }
            if (sourceMtime == null) {
                continue; // (a) top-level source is gone - the original #414 hazard
            }

            // (b) causality, not a timing window: a .class file cannot have been compiled from
            // source content that did not exist yet, so a genuinely current class's compiled
            // output is never older than its own top-level source file's last save. A stale
            // leftover - written by a compile that predates the source's later edit removing it -
            // falls behind the source's CURRENT timestamp regardless of how soon afterward the
            // file was recompiled, closing the race a same-compile-file tolerance window could not
            // (reviewed and replaced - see the class javadoc).
            if (entry.getValue().compareTo(sourceMtime) < 0) {
                continue;
            }

            result.add(fqcn);
        }
        return result;
    }

    private static String topLevelOf(String fqcn) {
        int dollar = fqcn.indexOf('$');
        return dollar < 0 ? fqcn : fqcn.substring(0, dollar);
    }
}
