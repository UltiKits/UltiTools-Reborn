package com.ultikits.ultitools.buildtools.deprecation;

import com.ultikits.ultitools.utils.VersionComparatorUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The release-boundary decision logic (Phase 21 decision D-12, superseding the D-03/D-05
 * empty-list rule): when {@code ${project.version}} carries no {@code -SNAPSHOT}, the configured
 * {@code japicmp.baseline.version} must name a released (non-SNAPSHOT) version strictly less than
 * the project version, or the build fails.
 *
 * <p>A formal release keeps comparing against the previous released version and keeps the exact
 * japicmp {@code <excludes>} entries the removal ledger registers - the list is <em>not</em>
 * required to be empty at the release boundary. Whether each remaining entry is legitimate is
 * decided, on every build, by the two existing exclusion checks rather than by a second removal
 * model here: {@link RemovalConsistencyEvaluator} rejects an exclusion the ledger does not
 * register, a registered removal that lacks its exclusion, and an incompatibility that is neither
 * excluded nor registered; {@link OverBroadExclusionInvariant} rejects a whole-class exclusion of a
 * class that still exists. After the formal release is downloadable from Maven Central, the next
 * development cycle advances the baseline to it and retires those exclusions - once a removal is
 * no longer newer than the baseline, {@link RemovalConsistencyEvaluator} stops requiring it.
 *
 * <p>Pure logic over three already-derived inputs - the project version string, the configured
 * japicmp baseline version string, and the pom's {@code <exclude>} key set - mirroring
 * {@link RemovalConsistencyEvaluator}'s separation from file I/O so this is unit-testable without
 * running Maven. {@link DeprecationRegistryGenerator} is the sole caller, reading all three inputs
 * from the same parsed {@code pom.xml} {@link org.w3c.dom.Document} it already holds. The check is
 * dormant on every {@code -SNAPSHOT} build.
 */
public final class ReleaseBoundaryInvariant {

    private static final String SNAPSHOT_MARKER = "SNAPSHOT";

    private ReleaseBoundaryInvariant() {
    }

    /**
     * Evaluates the D-12 invariant. Returns an empty list when {@code projectVersion} carries the
     * SNAPSHOT marker (the invariant is dormant during the development cycle); otherwise returns at
     * most one violation, naming why the baseline is not a usable released predecessor.
     *
     * @param projectVersion  {@code ${project.version}} as read from {@code pom.xml}
     * @param baselineVersion the configured {@code japicmp.baseline.version}, possibly {@code null}
     *                        or blank
     * @param excludeKeys     the japicmp {@code <exclude>} entries currently in {@code pom.xml}; must
     *                        not be {@code null} (an unread exclude list is not an empty one). Their
     *                        legitimacy is judged by {@link RemovalConsistencyEvaluator} and
     *                        {@link OverBroadExclusionInvariant}, not by their count
     * @return violation messages, empty when the invariant holds (or is dormant)
     */
    public static List<String> evaluate(String projectVersion, String baselineVersion, Set<RegistryKey> excludeKeys) {
        Objects.requireNonNull(excludeKeys, "excludeKeys");
        List<String> violations = new ArrayList<>();
        if (isSnapshot(projectVersion)) {
            return violations;
        }

        if (isSnapshot(baselineVersion)) {
            violations.add("the japicmp baseline version '" + baselineVersion + "' is not a released "
                    + "version - a release of '" + projectVersion + "' must compare against the "
                    + "previous released version on Maven Central, never an unpublished SNAPSHOT (D-12)");
        } else if (!isOlderBaseline(baselineVersion, projectVersion)) {
            violations.add("the japicmp baseline version '" + baselineVersion + "' must name the "
                    + "previous released version before releasing '" + projectVersion + "' - the "
                    + "baseline must be strictly less than the project version (D-12)");
        }

        return violations;
    }

    private static boolean isSnapshot(String version) {
        return version != null
                && version.toUpperCase(Locale.ROOT).contains(SNAPSHOT_MARKER);
    }

    /**
     * Fail-closed: a {@code null} or blank baseline is never treated as a released predecessor -
     * absence of data is not evidence that a previous release exists.
     */
    private static boolean isOlderBaseline(String baselineVersion, String projectVersion) {
        if (baselineVersion == null || baselineVersion.trim().isEmpty()) {
            return false;
        }
        return VersionComparatorUtil.isLessThan(baselineVersion, projectVersion);
    }
}
