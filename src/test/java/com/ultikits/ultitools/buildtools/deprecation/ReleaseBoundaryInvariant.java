package com.ultikits.ultitools.buildtools.deprecation;

import com.ultikits.ultitools.utils.VersionComparatorUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The D-03 decision logic: when {@code ${project.version}} carries no {@code -SNAPSHOT}, a release
 * must carry an empty japicmp {@code <excludes>} list AND a {@code japicmp.baseline.version}
 * strictly less than the project version, or the build fails.
 *
 * <p>Pure logic over three already-derived inputs - the project version string, the configured
 * japicmp baseline version string, and the pom's {@code <exclude>} key set - mirroring
 * {@link RemovalConsistencyEvaluator}'s separation from file I/O so this is unit-testable without
 * running Maven. {@link DeprecationRegistryGenerator} is the sole caller, reading all three inputs
 * from the same parsed {@code pom.xml} {@link org.w3c.dom.Document} it already holds.
 *
 * <p>D-05: "zero tolerance" means the exclusion list is empty at every <em>release boundary</em>,
 * not permanently empty. Entries are legal mid-cycle - this check is dormant on every
 * {@code -SNAPSHOT} build (today's 64 exclusions and 6.2.5 baseline build green) and only turns
 * red the moment {@code -SNAPSHOT} is dropped.
 */
public final class ReleaseBoundaryInvariant {

    private static final String SNAPSHOT_MARKER = "SNAPSHOT";

    private ReleaseBoundaryInvariant() {
    }

    /**
     * Evaluates the D-03 invariant. Returns an empty list when {@code projectVersion} carries the
     * SNAPSHOT marker (the invariant is dormant during the development cycle); otherwise returns
     * one violation per rule that fails - never more than two, since exactly two rules exist.
     *
     * @param projectVersion  {@code ${project.version}} as read from {@code pom.xml}
     * @param baselineVersion the configured {@code japicmp.baseline.version}, possibly {@code null}
     *                        or blank
     * @param excludeKeys     the japicmp {@code <exclude>} entries currently in {@code pom.xml}
     * @return violation messages, empty when the invariant holds (or is dormant)
     */
    public static List<String> evaluate(String projectVersion, String baselineVersion, Set<RegistryKey> excludeKeys) {
        List<String> violations = new ArrayList<>();
        if (isSnapshot(projectVersion)) {
            return violations;
        }

        int excludeCount = excludeKeys == null ? 0 : excludeKeys.size();
        if (excludeCount > 0) {
            violations.add("the japicmp <excludes> list still has " + excludeCount
                    + (excludeCount == 1 ? " entry" : " entries")
                    + "; a release must carry an empty list at the release boundary (D-05)");
        }

        if (!isAdvancedBaseline(baselineVersion, projectVersion)) {
            violations.add("the japicmp baseline version '" + baselineVersion + "' must be advanced to "
                    + "the previous released version before releasing '" + projectVersion + "' - the "
                    + "baseline must be strictly less than the project version");
        }

        return violations;
    }

    private static boolean isSnapshot(String projectVersion) {
        return projectVersion != null
                && projectVersion.toUpperCase(Locale.ROOT).contains(SNAPSHOT_MARKER);
    }

    /**
     * Fail-closed: a {@code null} or blank baseline is never treated as "advanced" - absence of
     * data is not evidence the baseline was bumped.
     */
    private static boolean isAdvancedBaseline(String baselineVersion, String projectVersion) {
        if (baselineVersion == null || baselineVersion.trim().isEmpty()) {
            return false;
        }
        return VersionComparatorUtil.isLessThan(baselineVersion, projectVersion);
    }
}
