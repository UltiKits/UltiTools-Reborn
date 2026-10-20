package com.ultikits.ultitools.buildtools.deprecation;

import com.ultikits.ultitools.utils.VersionComparatorUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The D-07 decision logic: a {@code {@removeIn}} promise stops being prose in a JSON file and
 * becomes a testable claim. An {@code ANNOUNCED} registry entry whose {@code removeIn} the project
 * version has reached, while the member is still declared in the fresh source scan, is a violation.
 *
 * <p>Pure logic over three already-derived inputs - the project version string, the merged
 * {@link RegistryLedger}, and the set of keys the current {@link JavadocDeprecationScanner} run
 * still finds in source - mirroring {@link RemovalConsistencyEvaluator} and
 * {@link ReleaseBoundaryInvariant}'s separation from file I/O so this is unit-testable without
 * running Maven. {@link DeprecationRegistryGenerator} is the sole caller.
 *
 * <p>An entry that has already transitioned to {@code REMOVED} is never flagged, whatever its
 * {@code removeIn} says - the promise was kept, and the reached-deadline check exists only to catch
 * an unkept one. Likewise an entry no longer present in the fresh source scan is never flagged here:
 * its removal is the merge's business ({@link RegistryLedger#merge}), not this evaluator's - by the
 * time an ANNOUNCED entry is absent from source, either the merge has already promoted it to
 * {@code REMOVED} (and the REMOVED guard above already exempts it) or the merge itself is about to
 * fail on the resulting {@link LedgerMergeConflictException}.
 *
 * <p>Reuses {@link VersionComparatorUtil} rather than a hand-rolled suffix strip (T-08-09): its
 * pre-release priority table already ranks {@code SNAPSHOT} below the bare release of the same
 * numeric core, so {@code 6.4.0-SNAPSHOT} compares strictly less than {@code 6.4.0} and the two
 * "building toward, not at" cases fall out without a special case.
 */
public final class RemovalDeadlineEvaluator {

    private RemovalDeadlineEvaluator() {
    }

    /**
     * Evaluates the D-07 deadline rule over every {@code ANNOUNCED} entry in {@code ledger}, in the
     * ledger's own stable order ({@link RegistryLedger#entries()} sorts by {@link RegistryKey}'s
     * total order), so two runs over unchanged content produce identical failure text.
     *
     * @param projectVersion   {@code ${project.version}} as read from {@code pom.xml}
     * @param ledger           the merged registry ledger for this run
     * @param presentInSource  the key set of the fresh {@link JavadocDeprecationScanner} scan -
     *                         members still declared in {@code src/main/java} right now
     * @return violation messages, empty when no ANNOUNCED entry's deadline has been reached, or the
     *         deadline was reached and kept
     */
    public static List<String> evaluate(
            String projectVersion, RegistryLedger ledger, Set<RegistryKey> presentInSource) {
        List<String> violations = new ArrayList<>();
        for (DeprecationEntry entry : ledger.entries()) {
            if (entry.getStatus() != DeprecationEntry.Status.ANNOUNCED) {
                continue; // only an announced promise can expire; REMOVED already kept it
            }
            String removeIn = entry.getRemoveIn();
            if (removeIn == null || removeIn.trim().isEmpty()) {
                continue; // fail-closed the other way: no deadline recorded, nothing to expire
            }
            if (!presentInSource.contains(entry.getKey())) {
                continue; // no longer declared in source - the merge's business, not this check's
            }
            if (VersionComparatorUtil.compare(projectVersion, removeIn) >= 0) {
                violations.add(buildMessage(entry.getKey(), removeIn, projectVersion));
            }
        }
        return violations;
    }

    private static String buildMessage(RegistryKey key, String removeIn, String projectVersion) {
        return "registry entry '" + key + "' has removeIn '" + removeIn + "', which project version '"
                + projectVersion + "' has reached, but the member is still declared in source - "
                + "either remove it, or move the deadline with a recorded reason";
    }
}
