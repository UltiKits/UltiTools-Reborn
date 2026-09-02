package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pins {@link RemovalDeadlineEvaluator}'s D-07 decision logic: an {@code ANNOUNCED} registry entry
 * whose {@code removeIn} the project version has reached, while the member is still declared in the
 * fresh source scan, is a violation naming the member (08-03-PLAN.md {@code <feature><behavior>}
 * block - seven named cases, one per bullet).
 */
@DisplayName("RemovalDeadlineEvaluator tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RemovalDeadlineEvaluatorTest {

    private static final RegistryKey KEY = RegistryKey.forClass("com.ultikits.ultitools.example.Removed");

    private static DeprecationEntry entry(DeprecationEntry.Status status, String removeIn) {
        return DeprecationEntry.builder()
                .key(KEY)
                .kind(DeprecationEntry.Kind.CLASS)
                .status(status)
                .removeIn(removeIn)
                .removedIn(status == DeprecationEntry.Status.REMOVED ? removeIn : null)
                .build();
    }

    private static RegistryLedger ledgerOf(DeprecationEntry entry) {
        return RegistryLedger.of(Collections.singletonList(entry));
    }

    private static Set<RegistryKey> presentSet(RegistryKey... keys) {
        Set<RegistryKey> set = new LinkedHashSet<>();
        for (RegistryKey key : keys) {
            set.add(key);
        }
        return set;
    }

    @Test
    @DisplayName("project version equal to removeIn, still in source, ANNOUNCED - violation naming the member")
    void reachedDeadlineExactlyViolates() {
        List<String> violations = RemovalDeadlineEvaluator.evaluate(
                "6.4.0", ledgerOf(entry(DeprecationEntry.Status.ANNOUNCED, "6.4.0")), presentSet(KEY));

        assertThat(violations)
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains(KEY.toString()).contains("6.4.0"));
    }

    @Test
    @DisplayName("project version past removeIn, still in source, ANNOUNCED - violation")
    void reachedDeadlinePastViolates() {
        List<String> violations = RemovalDeadlineEvaluator.evaluate(
                "6.4.1", ledgerOf(entry(DeprecationEntry.Status.ANNOUNCED, "6.4.0")), presentSet(KEY));

        assertThat(violations)
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains(KEY.toString()));
    }

    @Test
    @DisplayName("SNAPSHOT of the removeIn release is building toward it, not at it - no violation")
    void snapshotBuildingTowardDeadlineIsClean() {
        List<String> violations = RemovalDeadlineEvaluator.evaluate(
                "6.4.0-SNAPSHOT", ledgerOf(entry(DeprecationEntry.Status.ANNOUNCED, "6.4.0")), presentSet(KEY));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("SNAPSHOT well before the deadline release - no violation")
    void snapshotBeforeDeadlineIsClean() {
        List<String> violations = RemovalDeadlineEvaluator.evaluate(
                "6.3.0-SNAPSHOT", ledgerOf(entry(DeprecationEntry.Status.ANNOUNCED, "6.4.0")), presentSet(KEY));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("deadline reached but the promise was already kept (status REMOVED) - no violation")
    void alreadyRemovedEntryIsClean() {
        List<String> violations = RemovalDeadlineEvaluator.evaluate(
                "6.4.0", ledgerOf(entry(DeprecationEntry.Status.REMOVED, "6.4.0")), presentSet());

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("deadline reached, still ANNOUNCED, but absent from the fresh source scan - no violation")
    void announcedButAbsentFromSourceIsClean() {
        List<String> violations = RemovalDeadlineEvaluator.evaluate(
                "6.4.0", ledgerOf(entry(DeprecationEntry.Status.ANNOUNCED, "6.4.0")), presentSet());

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("a null or blank removeIn never expires, whatever the status")
    void nullOrBlankRemoveInIsClean() {
        List<String> nullRemoveInViolations = RemovalDeadlineEvaluator.evaluate(
                "6.4.0", ledgerOf(entry(DeprecationEntry.Status.ANNOUNCED, null)), presentSet(KEY));
        List<String> blankRemoveInViolations = RemovalDeadlineEvaluator.evaluate(
                "6.4.0", ledgerOf(entry(DeprecationEntry.Status.ANNOUNCED, "   ")), presentSet(KEY));

        assertThat(nullRemoveInViolations).isEmpty();
        assertThat(blankRemoveInViolations).isEmpty();
    }
}
