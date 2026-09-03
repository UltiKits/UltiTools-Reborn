package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pins {@link ReleaseBoundaryInvariant}'s D-03 decision logic: when {@code ${project.version}}
 * carries no {@code -SNAPSHOT}, the japicmp {@code <excludes>} list must be empty and
 * {@code japicmp.baseline.version} must be strictly less than the project version, or the build
 * fails (08-02-PLAN.md must_haves.truths #2/#3, behaviour block).
 */
@DisplayName("ReleaseBoundaryInvariant tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ReleaseBoundaryInvariantTest {

    private static Set<RegistryKey> excludeKeysOfSize(int count) {
        Set<RegistryKey> keys = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            keys.add(RegistryKey.forClass("com.example.Removed" + i));
        }
        return keys;
    }

    @Test
    @DisplayName("dormant on a -SNAPSHOT project version, regardless of excludes or baseline")
    void snapshotVersionIsDormant() {
        List<String> violations = ReleaseBoundaryInvariant.evaluate(
                "6.3.0-SNAPSHOT", "6.2.5", excludeKeysOfSize(64));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("non-SNAPSHOT release with a non-empty exclusion list is flagged (D-05)")
    void nonEmptyExclusionListViolatesOnRelease() {
        List<String> violations = ReleaseBoundaryInvariant.evaluate(
                "6.3.0", "6.2.5", excludeKeysOfSize(64));

        assertThat(violations)
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("64").contains("D-05"));
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
}
