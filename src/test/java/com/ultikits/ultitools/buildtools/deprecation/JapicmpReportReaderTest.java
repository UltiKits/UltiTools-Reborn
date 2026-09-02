package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pins {@link JapicmpReportReader}'s parse of {@code target/japicmp/japicmp.xml} into the same
 * {@link RegistryKey} identifier space the registry and the pom {@code <exclude>} entries use
 * (D-22), plus the old-side access modifier and root scope {@link RemovalConsistencyEvaluator}
 * needs for D-01/D-21.
 */
@DisplayName("JapicmpReportReader tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JapicmpReportReaderTest {

    private static final Path FIXTURE_DIR = Paths.get("src/test/resources/buildtools/deprecation");

    private static String readFixture(String name) throws IOException {
        return new String(Files.readAllBytes(FIXTURE_DIR.resolve(name)), StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("whole-class removal")
    class WholeClassRemovalTests {

        @Test
        @DisplayName("Test 1: a whole-class removal parses to one class-level RegistryKey with changeStatus REMOVED")
        void wholeClassRemovalParsesToClassLevelKey() throws IOException {
            JapicmpReportReader.Report report = JapicmpReportReader.parse(
                    readFixture("removed-class.xml"), "removed-class.xml");

            RegistryKey classKey = RegistryKey.forClass("com.ultikits.ultitools.aop.AopProxyBeanPostProcessor");
            assertThat(report.entries()).containsKey(classKey);
            assertThat(report.find(classKey).get().getChangeStatus()).isEqualTo("REMOVED");
        }
    }

    @Nested
    @DisplayName("member removal - parameter rendering")
    class MemberRemovalParameterTests {

        @Test
        @DisplayName("Test 2: a removed method's parameter types are fully qualified, comma-separated, and byte-identical to RegistryKey's own rendering")
        void removedMethodParamsRenderIdenticallyToRegistryKey() throws IOException {
            JapicmpReportReader.Report report = JapicmpReportReader.parse(
                    readFixture("removed-member-multi-param.xml"), "removed-member-multi-param.xml");

            RegistryKey expected = RegistryKey.forMember(
                    "com.ultikits.ultitools.context.SomeSurvivingProcessor",
                    "postProcessAfterInitialization",
                    Arrays.asList("java.lang.Object", "java.lang.String"));

            assertThat(report.entries()).containsKey(expected);
            assertThat(expected.toString())
                    .isEqualTo("com.ultikits.ultitools.context.SomeSurvivingProcessor"
                            + "#postProcessAfterInitialization(java.lang.Object,java.lang.String)");
            assertThat(report.find(expected).get().getChangeStatus()).isEqualTo("REMOVED");
        }

        @Test
        @DisplayName("Test 3: a zero-argument removed method parses to a key ending in empty parens")
        void zeroArgumentRemovedMethodEndsInEmptyParens() throws IOException {
            JapicmpReportReader.Report report = JapicmpReportReader.parse(
                    readFixture("removed-class.xml"), "removed-class.xml");

            RegistryKey zeroArgKey = RegistryKey.forMember(
                    "com.ultikits.ultitools.aop.AopProxyBeanPostProcessor",
                    "getAdvisors", Collections.emptyList());

            assertThat(zeroArgKey.toString()).endsWith("getAdvisors()");
            assertThat(report.entries()).containsKey(zeroArgKey);
            assertThat(report.find(zeroArgKey).get().getChangeStatus()).isEqualTo("REMOVED");
        }
    }

    @Nested
    @DisplayName("old-side access modifier surfacing (D-21)")
    class OldAccessModifierTests {

        @Test
        @DisplayName("Test 4: <modifier oldValue=\"PRIVATE\"> is surfaced as the member's old-side modifier PRIVATE")
        void privateOldSideModifierIsSurfaced() throws IOException {
            JapicmpReportReader.Report report = JapicmpReportReader.parse(
                    readFixture("modifiers.xml"), "modifiers.xml");

            RegistryKey key = RegistryKey.forMember(
                    "com.ultikits.ultitools.commands.tabcomplete.SomeCompleter",
                    "wasPrivateNowPublicStatic",
                    Arrays.asList("java.lang.Object", "java.lang.String"));

            assertThat(report.find(key)).isPresent();
            assertThat(report.find(key).get().getOldAccessModifier()).isEqualTo("PRIVATE");
        }

        @Test
        @DisplayName("Test 5: <modifier oldValue=\"PUBLIC\"> is surfaced as PUBLIC")
        void publicOldSideModifierIsSurfaced() throws IOException {
            JapicmpReportReader.Report report = JapicmpReportReader.parse(
                    readFixture("modifiers.xml"), "modifiers.xml");

            RegistryKey key = RegistryKey.forMember(
                    "com.ultikits.ultitools.commands.tabcomplete.SomeCompleter",
                    "wasPublicStillPublic", Collections.emptyList());

            assertThat(report.find(key)).isPresent();
            assertThat(report.find(key).get().getOldAccessModifier()).isEqualTo("PUBLIC");
        }
    }

    @Nested
    @DisplayName("empty-input edge case (GEN-04)")
    class EmptyInputTests {

        @Test
        @DisplayName("Test 6: an XML file with no classes parses to an empty result set, not a throw and not null")
        void noIncompatibleEntriesParsesToEmptyResultSet() throws IOException {
            JapicmpReportReader.Report report = JapicmpReportReader.parse(readFixture("empty.xml"), "empty.xml");

            assertThat(report).isNotNull();
            assertThat(report.entries()).isNotNull();
            assertThat(report.entries()).isEmpty();
        }
    }

    @Nested
    @DisplayName("malformed and absent input never pass vacuously")
    class FailureModeTests {

        @Test
        @DisplayName("Test 7a: a malformed XML file produces a named, actionable failure naming the source")
        void malformedXmlNamesTheSource() {
            Path malformed = FIXTURE_DIR.resolve("malformed.xml");

            assertThatThrownBy(() -> JapicmpReportReader.read(malformed))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(malformed.toString());
        }

        @Test
        @DisplayName("Test 7b: reading an absent file produces an exception whose message contains the attempted path")
        void absentFileNamesTheAttemptedPath() {
            Path missing = FIXTURE_DIR.resolve("does-not-exist.xml");

            assertThatThrownBy(() -> JapicmpReportReader.read(missing))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(missing.toString());
        }
    }

    @Nested
    @DisplayName("root scope exposure (D-22 scope equality)")
    class ScopeExposureTests {

        @Test
        @DisplayName("Test 8: the parser reads and exposes the root accessModifier attribute")
        void rootAccessModifierIsExposed() throws IOException {
            JapicmpReportReader.Report report = JapicmpReportReader.parse(readFixture("empty.xml"), "empty.xml");

            assertThat(report.accessModifier()).isEqualTo("PROTECTED");
        }
    }
}
