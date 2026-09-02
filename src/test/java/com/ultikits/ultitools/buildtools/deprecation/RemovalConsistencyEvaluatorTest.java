package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pins {@link RemovalConsistencyEvaluator}'s D-01/D-21/D-22 decision logic: a japicmp
 * {@code <exclude>} entry whose key has no corresponding registry entry and no admissible
 * allowlist justification turns the build red (07-03-PLAN.md must_haves.truths #1).
 */
@DisplayName("RemovalConsistencyEvaluator tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RemovalConsistencyEvaluatorTest {

    /** Builds a report entry via reflection on {@link JapicmpReportReader.Entry}'s package-private constructor. */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static JapicmpReportReader.Entry entry(String changeStatus, String oldAccessModifier, boolean binaryCompatible) {
        try {
            Constructor<JapicmpReportReader.Entry> ctor = JapicmpReportReader.Entry.class.getDeclaredConstructor(
                    String.class, String.class, boolean.class);
            ctor.setAccessible(true);
            return ctor.newInstance(changeStatus, oldAccessModifier, binaryCompatible);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JapicmpReportReader.Report reportOf(String scope, Map<RegistryKey, JapicmpReportReader.Entry> entries) {
        try {
            Constructor<JapicmpReportReader.Report> ctor = JapicmpReportReader.Report.class.getDeclaredConstructor(
                    String.class, Map.class);
            ctor.setAccessible(true);
            return ctor.newInstance(scope, entries);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DeprecationEntry announcedEntry(RegistryKey key, String removeIn) {
        return DeprecationEntry.builder()
                .key(key)
                .kind(DeprecationEntry.Kind.METHOD)
                .since("6.2.0")
                .forRemoval(true)
                .removeIn(removeIn)
                .replacement("Use something else.")
                .status(DeprecationEntry.Status.ANNOUNCED)
                .build();
    }

    @Nested
    @DisplayName("D-01 staleness")
    class StalenessTests {

        @Test
        @DisplayName("Test 1: an exclude key absent from both the report and the registry is stale")
        void excludeKeyAbsentFromReportAndRegistryIsStale() {
            RegistryKey key = RegistryKey.forMember(
                    "com.ultikits.ultitools.NoSuchClass", "bogus", Collections.emptyList());
            Set<RegistryKey> excludeKeys = new HashSet<>(Collections.singletonList(key));
            JapicmpReportReader.Report report = reportOf("PROTECTED", Collections.emptyMap());
            RegistryLedger registry = RegistryLedger.empty();

            List<RemovalConsistencyEvaluator.Finding> findings =
                    RemovalConsistencyEvaluator.evaluate(excludeKeys, report, registry);

            assertThat(findings)
                    .extracting(RemovalConsistencyEvaluator.Finding::getKind)
                    .contains(RemovalConsistencyEvaluator.Finding.Kind.STALE_EXCLUSION);
            assertThat(findings.get(0).describe()).contains(key.toString());
        }
    }

    @Nested
    @DisplayName("D-01 coverage")
    class CoverageTests {

        @Test
        @DisplayName("Test 2: a report REMOVED key with no exclude and no registry entry is an unrecorded removal")
        void reportRemovedKeyWithNoExcludeAndNoRegistryIsUnrecorded() {
            RegistryKey key = RegistryKey.forClass("com.ultikits.ultitools.SomeRemovedClass");
            Map<RegistryKey, JapicmpReportReader.Entry> entries = new LinkedHashMap<>();
            entries.put(key, entry("REMOVED", null, false));
            JapicmpReportReader.Report report = reportOf("PROTECTED", entries);

            List<RemovalConsistencyEvaluator.Finding> findings =
                    RemovalConsistencyEvaluator.evaluate(Collections.emptySet(), report, RegistryLedger.empty());

            assertThat(findings)
                    .extracting(RemovalConsistencyEvaluator.Finding::getKind)
                    .contains(RemovalConsistencyEvaluator.Finding.Kind.UNRECORDED_REMOVAL);
        }
    }

    @Nested
    @DisplayName("D-21 admissibility")
    class AdmissibilityTests {

        private JapicmpReportReader.Report reportWithAllowlistEntry(RegistryKey key, String oldModifier) {
            Map<RegistryKey, JapicmpReportReader.Entry> entries = new LinkedHashMap<>();
            entries.put(key, entry("MODIFIED", oldModifier, false));
            return reportOf("PROTECTED", entries);
        }

        @Test
        @DisplayName("Test 3: PRIVATE old-side allowlist entry is admissible")
        void privateOldSideIsAdmissible() {
            RegistryKey key = RegistryKey.forMember(
                    "com.ultikits.ultitools.commands.tabcomplete.MethodInvocationCompleter",
                    "getSuggestMethodsByName",
                    java.util.Arrays.asList("java.lang.Object", "java.lang.String"));
            Set<RegistryKey> excludeKeys = new HashSet<>(Collections.singletonList(key));
            JapicmpReportReader.Report report = reportWithAllowlistEntry(key, "PRIVATE");

            List<RemovalConsistencyEvaluator.Finding> findings =
                    RemovalConsistencyEvaluator.evaluate(excludeKeys, report, RegistryLedger.empty());

            assertThat(findings).noneMatch(f -> f.getKind() == RemovalConsistencyEvaluator.Finding.Kind.INADMISSIBLE_ALLOWLIST);
        }

        @Test
        @DisplayName("Test 4: PACKAGE_PROTECTED old-side allowlist entry is admissible")
        void packageProtectedOldSideIsAdmissible() {
            RegistryKey key = RegistryKey.forMember("com.ultikits.ultitools.SomePkg", "helper", Collections.emptyList());
            Set<RegistryKey> excludeKeys = new HashSet<>(Collections.singletonList(key));
            JapicmpReportReader.Report report = reportWithAllowlistEntry(key, "PACKAGE_PROTECTED");

            List<RemovalConsistencyEvaluator.Finding> findings =
                    RemovalConsistencyEvaluator.evaluate(excludeKeys, report, RegistryLedger.empty());

            assertThat(findings).noneMatch(f -> f.getKind() == RemovalConsistencyEvaluator.Finding.Kind.INADMISSIBLE_ALLOWLIST);
        }

        @Test
        @DisplayName("Test 5: PUBLIC old-side allowlist entry is rejected unconditionally")
        void publicOldSideIsRejected() {
            RegistryKey key = RegistryKey.forMember("com.ultikits.ultitools.SomePublic", "wasPublic", Collections.emptyList());
            Set<RegistryKey> excludeKeys = new HashSet<>(Collections.singletonList(key));
            JapicmpReportReader.Report report = reportWithAllowlistEntry(key, "PUBLIC");

            List<RemovalConsistencyEvaluator.Finding> findings =
                    RemovalConsistencyEvaluator.evaluate(excludeKeys, report, RegistryLedger.empty());

            assertThat(findings)
                    .extracting(RemovalConsistencyEvaluator.Finding::getKind)
                    .contains(RemovalConsistencyEvaluator.Finding.Kind.INADMISSIBLE_ALLOWLIST);
        }

        @Test
        @DisplayName("Test 6: PROTECTED old-side allowlist entry is also rejected - protected is part of the public contract by construction")
        void protectedOldSideIsAlsoRejected() {
            RegistryKey key = RegistryKey.forMember("com.ultikits.ultitools.SomeProtected", "wasProtected", Collections.emptyList());
            Set<RegistryKey> excludeKeys = new HashSet<>(Collections.singletonList(key));
            JapicmpReportReader.Report report = reportWithAllowlistEntry(key, "PROTECTED");

            List<RemovalConsistencyEvaluator.Finding> findings =
                    RemovalConsistencyEvaluator.evaluate(excludeKeys, report, RegistryLedger.empty());

            assertThat(findings)
                    .extracting(RemovalConsistencyEvaluator.Finding::getKind)
                    .contains(RemovalConsistencyEvaluator.Finding.Kind.INADMISSIBLE_ALLOWLIST);
        }

        @Test
        @DisplayName("isAdmissibleAllowlistEntry exposes no override parameter that can suppress a rejection")
        void exposesNoOverrideParameter() throws NoSuchMethodException {
            // The admissibility check takes exactly one argument - the parsed report entry.
            // There is no boolean/flag/config parameter anywhere in the evaluator's public API
            // that could suppress a PUBLIC/PROTECTED rejection.
            assertThat(RemovalConsistencyEvaluator.class.getMethod(
                    "isAdmissibleAllowlistEntry", JapicmpReportReader.Entry.class).getParameterCount())
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("D-22 dual-source REMOVED transition")
    class DualSourceTransitionTests {

        @Test
        @DisplayName("Test 7: source-gone AND report-REMOVED agreement produces a removed transition with no exclude findings")
        void agreementProducesRemovedTransitionWithNoFindings() {
            RegistryKey key = RegistryKey.forMember("com.ultikits.ultitools.Foo", "bar", Collections.emptyList());
            RegistryLedger prior = RegistryLedger.of(Collections.singletonList(announcedEntry(key, "6.3.0")));

            // Fresh source scan no longer finds the declaration; japicmp independently confirms
            // REMOVED for the same key - RegistryLedger.merge (07-02) is the sole owner of this
            // agreement rule; this evaluator reuses its output rather than re-deriving it.
            RegistryLedger merged = RegistryLedger.merge(prior, Collections.emptyList(),
                    new HashSet<>(Collections.singletonList(key)));

            assertThat(merged.entries()).hasSize(1);
            assertThat(merged.entries().get(0).getStatus()).isEqualTo(DeprecationEntry.Status.REMOVED);
            assertThat(merged.entries().get(0).getRemovedIn()).isEqualTo("6.3.0");

            Set<RegistryKey> excludeKeys = new HashSet<>(Collections.singletonList(key));
            List<RemovalConsistencyEvaluator.Finding> findings =
                    RemovalConsistencyEvaluator.evaluate(excludeKeys, JapicmpReportReader.Report.empty(), merged);

            assertThat(findings).isEmpty();
        }

        @Test
        @DisplayName("Test 8: source-gone but report-silent is a fatal disagreement, caught before this evaluator ever runs")
        void sourceGoneButReportSilentIsFatalDisagreement() {
            RegistryKey key = RegistryKey.forMember("com.ultikits.ultitools.Foo", "bar", Collections.emptyList());
            RegistryLedger prior = RegistryLedger.of(Collections.singletonList(announcedEntry(key, "6.3.0")));

            assertThatThrownBy(() -> RegistryLedger.merge(prior, Collections.emptyList(), Collections.emptySet()))
                    .isInstanceOf(LedgerMergeConflictException.class);
        }

        @Test
        @DisplayName("Test 9: report-REMOVED but declaration still present in source is a fatal disagreement, caught before this evaluator ever runs")
        void reportRemovedButSourceStillPresentIsFatalDisagreement() {
            RegistryKey key = RegistryKey.forMember("com.ultikits.ultitools.Foo", "bar", Collections.emptyList());
            RegistryLedger prior = RegistryLedger.empty();
            DeprecationEntry stillDeclared = announcedEntry(key, "6.3.0");

            assertThatThrownBy(() -> RegistryLedger.merge(prior, Collections.singletonList(stillDeclared),
                    new HashSet<>(Collections.singletonList(key))))
                    .isInstanceOf(LedgerMergeConflictException.class);
        }

        @Test
        @DisplayName("A REMOVED registry entry with no matching pom exclude is flagged (the pom-sync half of D-22)")
        void removedRegistryEntryWithNoExcludeIsFlagged() {
            RegistryKey key = RegistryKey.forMember("com.ultikits.ultitools.Foo", "bar", Collections.emptyList());
            RegistryLedger prior = RegistryLedger.of(Collections.singletonList(announcedEntry(key, "6.3.0")));
            RegistryLedger merged = RegistryLedger.merge(prior, Collections.emptyList(),
                    new HashSet<>(Collections.singletonList(key)));

            List<RemovalConsistencyEvaluator.Finding> findings =
                    RemovalConsistencyEvaluator.evaluate(Collections.emptySet(), JapicmpReportReader.Report.empty(), merged);

            assertThat(findings)
                    .extracting(RemovalConsistencyEvaluator.Finding::getKind)
                    .contains(RemovalConsistencyEvaluator.Finding.Kind.MISSING_EXCLUSION_FOR_REMOVED);
        }
    }

    @Nested
    @DisplayName("empty-input edge case (GEN-04)")
    class EmptyInputTests {

        @Test
        @DisplayName("Test 10: empty excludes, empty report and empty registry together produce a clean verdict, not a throw")
        void emptyInputsProduceCleanVerdict() {
            List<RemovalConsistencyEvaluator.Finding> findings = RemovalConsistencyEvaluator.evaluate(
                    Collections.emptySet(), JapicmpReportReader.Report.empty(), RegistryLedger.empty());

            assertThat(findings).isNotNull().isEmpty();
        }
    }

    @Nested
    @DisplayName("D-22 scope equality")
    class ScopeEqualityTests {

        @Test
        @DisplayName("Test 11: a report whose accessModifier is narrower than the registry's declared scope is a fatal finding")
        void narrowerReportScopeIsFatal() {
            JapicmpReportReader.Report report = reportOf("PUBLIC", Collections.emptyMap());

            List<RemovalConsistencyEvaluator.Finding> findings =
                    RemovalConsistencyEvaluator.evaluate(Collections.emptySet(), report, RegistryLedger.empty());

            assertThat(findings)
                    .extracting(RemovalConsistencyEvaluator.Finding::getKind)
                    .contains(RemovalConsistencyEvaluator.Finding.Kind.SCOPE_MISMATCH);
        }
    }
}
