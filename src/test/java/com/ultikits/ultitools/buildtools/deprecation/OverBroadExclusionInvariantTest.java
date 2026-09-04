package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.w3c.dom.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Pins {@link OverBroadExclusionInvariant}'s #401 rule: a bare fully-qualified-name (whole-class)
 * japicmp {@code <exclude>} entry is legitimate only for a class that no longer exists in the build
 * output. Synthetic cases cover the discrimination logic; the final two tests run the rule against
 * the real {@code pom.xml} and the real {@code target/classes}, which is what gates the build.
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
    @DisplayName("the real check: the real pom.xml against the real target/classes produces zero violations")
    void realPomAgainstRealBuildOutputIsClean() throws IOException {
        Document pomDocument = DeprecationRegistryGenerator.readPomDocument();
        Set<RegistryKey> excludeKeys = DeprecationRegistryGenerator.readPomExcludeKeys(pomDocument);
        Set<String> classesInBuildOutput = scanBuildOutputClasses();

        List<String> violations = OverBroadExclusionInvariant.evaluate(excludeKeys, classesInBuildOutput);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("the real check fails closed rather than passing vacuously when target/classes is missing or empty")
    void realCheckFailsClosedOnMissingOrEmptyBuildOutput() throws IOException {
        Set<String> classesInBuildOutput = scanBuildOutputClasses();

        assertThat(classesInBuildOutput)
                .as("target/classes must contain compiled classes by the time this test runs - "
                        + "`compile` runs before `test` in the default Maven lifecycle. An empty scan "
                        + "here means something is structurally wrong (e.g. target/classes was cleaned "
                        + "or moved after compile), not that the check passed.")
                .isNotEmpty();
    }

    private static Set<RegistryKey> setOf(RegistryKey... keys) {
        return new LinkedHashSet<>(Arrays.asList(keys));
    }

    private static Set<String> setOf(String... classNames) {
        return new LinkedHashSet<>(Arrays.asList(classNames));
    }

    /**
     * Walks {@code target/classes} for {@code *.class} files and returns their fully-qualified names:
     * relativised against {@code target/classes}, the {@code .class} suffix dropped, the path
     * separator replaced with {@code .}, and any nested-class {@code $} left untouched.
     */
    private static Set<String> scanBuildOutputClasses() throws IOException {
        Path classesRoot = Paths.get("target", "classes");
        Set<String> classNames = new LinkedHashSet<>();
        if (!Files.isDirectory(classesRoot)) {
            return classNames;
        }
        try (Stream<Path> paths = Files.walk(classesRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        String relative = classesRoot.relativize(p).toString();
                        String withoutSuffix = relative.substring(0, relative.length() - ".class".length());
                        String fqcn = withoutSuffix.replace(java.io.File.separatorChar, '.');
                        classNames.add(fqcn);
                    });
        }
        return classNames;
    }
}
