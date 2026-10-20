package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pins {@link ReleaseBoundaryInvariant}'s release-boundary decision logic as approved in Phase 21
 * decision D-12: when {@code ${project.version}} carries no {@code -SNAPSHOT}, the japicmp
 * {@code japicmp.baseline.version} must be a released (non-SNAPSHOT) version strictly less than the
 * project version, or the build fails. The {@code <excludes>} list is NOT required to be empty at
 * the release boundary: a formal release keeps comparing against the previous released version and
 * keeps the exact exclusions the removal ledger registers. Whether each exclusion is legitimate is
 * decided by {@link RemovalConsistencyEvaluator} (registered, not stale, not missing) and
 * {@link OverBroadExclusionInvariant} (no whole-class exclusion of a surviving class) - pinned in the
 * {@code FormalReleaseGateComposition} cases below.
 */
@DisplayName("ReleaseBoundaryInvariant tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ReleaseBoundaryInvariantTest {

    private static final RegistryKey REMOVED_CLASS =
            RegistryKey.forClass("com.ultikits.ultitools.abstracts.AbstractCommandExecutor");
    private static final RegistryKey REMOVED_METHOD = RegistryKey.forMember(
            "com.ultikits.ultitools.UltiTools", "getVersionWrapper", Collections.<String>emptyList());
    private static final RegistryKey REMOVED_FIELD =
            RegistryKey.forField("com.ultikits.ultitools.UltiTools", "versionWrapper");

    private static Set<RegistryKey> excludeKeysOfSize(int count) {
        Set<RegistryKey> keys = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            keys.add(RegistryKey.forClass("com.example.Removed" + i));
        }
        return keys;
    }

    /** Exclusions exactly matching three removals the ledger records as REMOVED in 6.3.0. */
    private static Set<RegistryKey> registeredRemovalExcludes() {
        return new LinkedHashSet<>(Arrays.asList(REMOVED_CLASS, REMOVED_METHOD, REMOVED_FIELD));
    }

    @Test
    @DisplayName("dormant on a -SNAPSHOT project version, regardless of excludes or baseline")
    void snapshotVersionIsDormant() {
        List<String> violations = ReleaseBoundaryInvariant.evaluate(
                "6.3.0-SNAPSHOT", "6.2.5", excludeKeysOfSize(64));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("D-12: formal 6.3.0 against released baseline 6.2.5 keeps its registered-removal exclusions")
    void formalReleaseAgainstReleasedBaselineAcceptsRegisteredRemovalExclusions() {
        List<String> violations = ReleaseBoundaryInvariant.evaluate(
                "6.3.0", "6.2.5", registeredRemovalExcludes());

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("D-12: an invalid baseline is still the only violation when registered exclusions are present")
    void invalidBaselineIsTheOnlyViolationWhenRegisteredExclusionsArePresent() {
        List<String> equalBaseline = ReleaseBoundaryInvariant.evaluate(
                "6.3.0", "6.3.0", registeredRemovalExcludes());
        List<String> futureBaseline = ReleaseBoundaryInvariant.evaluate(
                "6.3.0", "6.4.0", registeredRemovalExcludes());
        List<String> missingBaseline = ReleaseBoundaryInvariant.evaluate(
                "6.3.0", null, registeredRemovalExcludes());

        assertThat(equalBaseline).hasSize(1).allSatisfy(v -> assertThat(v).contains("6.3.0"));
        assertThat(futureBaseline).hasSize(1).allSatisfy(v -> assertThat(v).contains("6.4.0"));
        assertThat(missingBaseline).hasSize(1);
    }

    @Test
    @DisplayName("non-SNAPSHOT release, empty excludes, baseline strictly behind - clean")
    void emptyExclusionListWithAdvancedBaselineIsClean() {
        List<String> violations = ReleaseBoundaryInvariant.evaluate(
                "6.3.0", "6.2.5", excludeKeysOfSize(0));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("baseline equal to the project version is not strictly behind - flagged")
    void baselineEqualToProjectVersionViolates() {
        List<String> violations = ReleaseBoundaryInvariant.evaluate(
                "6.3.0", "6.3.0", excludeKeysOfSize(0));

        assertThat(violations)
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("6.3.0"));
    }

    @Test
    @DisplayName("baseline newer than the project version is not strictly behind - flagged")
    void baselineNewerThanProjectVersionViolates() {
        List<String> violations = ReleaseBoundaryInvariant.evaluate(
                "6.3.0", "6.4.0", excludeKeysOfSize(0));

        assertThat(violations)
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("6.4.0").contains("6.3.0"));
    }

    @Test
    @DisplayName("a null or blank baseline fails closed on a non-SNAPSHOT release")
    void nullOrBlankBaselineFailsClosed() {
        List<String> nullBaselineViolations = ReleaseBoundaryInvariant.evaluate(
                "6.3.0", null, excludeKeysOfSize(0));
        List<String> blankBaselineViolations = ReleaseBoundaryInvariant.evaluate(
                "6.3.0", "   ", excludeKeysOfSize(0));

        assertThat(nullBaselineViolations).hasSize(1);
        assertThat(blankBaselineViolations).hasSize(1);
    }

    @Test
    @DisplayName("D-12: a -SNAPSHOT baseline is not a released version and fails closed on a formal release")
    void snapshotBaselineFailsClosedOnFormalRelease() {
        List<String> violations = ReleaseBoundaryInvariant.evaluate(
                "6.3.0", "6.2.5-SNAPSHOT", registeredRemovalExcludes());

        assertThat(violations)
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("6.2.5-SNAPSHOT").contains("released"));
    }

    /**
     * The formal-release gate is the three checks {@link DeprecationRegistryGenerator} and
     * {@code OverBroadExclusionInvariantTest} run together. With the empty-list rule gone, an
     * illegitimate exclusion at the release boundary must still turn the gate red through one of
     * the two existing exclusion checks - never pass because the boundary check stopped looking.
     */
    @Nested
    @DisplayName("D-12 formal release gate composition (6.3.0 against 6.2.5)")
    class FormalReleaseGateComposition {

        private static final String PROJECT_VERSION = "6.3.0";
        private static final String BASELINE = "6.2.5";

        @Test
        @DisplayName("a whole-class exclusion of a class still in the build output is rejected at the release boundary")
        void overBroadExclusionIsStillRejectedAtTheReleaseBoundary() {
            RegistryKey survivingClass = RegistryKey.forClass("com.ultikits.ultitools.UltiTools");
            Set<RegistryKey> excludeKeys = registeredRemovalExcludes();
            excludeKeys.add(survivingClass);
            Set<String> classesInBuildOutput = new HashSet<>(Collections.singletonList(
                    "com.ultikits.ultitools.UltiTools"));

            List<String> boundary = ReleaseBoundaryInvariant.evaluate(PROJECT_VERSION, BASELINE, excludeKeys);
            List<String> overBroad = OverBroadExclusionInvariant.evaluate(excludeKeys, classesInBuildOutput);

            assertThat(boundary).isEmpty();
            assertThat(overBroad)
                    .hasSize(1)
                    .allSatisfy(v -> assertThat(v).contains("com.ultikits.ultitools.UltiTools"));
        }

        @Test
        @DisplayName("exact registered-removal exclusions of classes absent from the build output pass every check")
        void registeredRemovalExclusionsPassTheBoundaryAndOverBroadChecks() {
            Set<String> classesInBuildOutput = new HashSet<>(Collections.singletonList(
                    "com.ultikits.ultitools.UltiTools"));

            List<String> boundary = ReleaseBoundaryInvariant.evaluate(
                    PROJECT_VERSION, BASELINE, registeredRemovalExcludes());
            List<String> overBroad = OverBroadExclusionInvariant.evaluate(
                    registeredRemovalExcludes(), classesInBuildOutput);

            assertThat(boundary).isEmpty();
            assertThat(overBroad).isEmpty();
        }
    }
}
