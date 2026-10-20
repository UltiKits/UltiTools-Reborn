package com.ultikits.ultitools.buildtools.deprecation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The rule closing #401: a bare fully-qualified-name (whole-class) japicmp {@code <exclude>} entry
 * removes the named class AND every one of its members from the binary-compatibility comparison,
 * permanently. That is legitimate only for a class that no longer exists at all - if the class is
 * still present in the current build output, the entry is over-broad by construction, silencing the
 * entire surviving public API of a class nobody removed.
 *
 * <p>Pure logic over two already-derived inputs - the pom's {@code <exclude>} key set and the set of
 * fully-qualified class names present in the build output - mirroring {@link ReleaseBoundaryInvariant}
 * and {@link RemovalDeadlineEvaluator}'s separation from file I/O so this is unit-testable without
 * running Maven and without a japicmp report. {@code OverBroadExclusionInvariantTest} is the sole
 * caller, reading both inputs itself: the exclude keys via {@link DeprecationRegistryGenerator}'s
 * package-private pom readers, and the build-output class names by walking {@code target/classes}.
 *
 * <p>A member-level or field-level key (see {@link RegistryKey#isClassLevel()}) is never a match for
 * this rule - naming an individual removed member is the correct instrument, and this rule exists to
 * steer authors toward it. This rule does not catch a bare-FQN entry that is simply a typo naming a
 * class that never existed; that failure mode is indistinguishable from a legitimate removed-class
 * entry by this check alone.
 */
public final class OverBroadExclusionInvariant {

    private OverBroadExclusionInvariant() {
    }

    /**
     * Evaluates the over-broad-exclusion rule. Returns one violation message per class-level
     * {@code excludeKeys} entry whose class name is present in {@code classesInBuildOutput} - never
     * throws on a rule violation, only on a {@code null} argument.
     *
     * @param excludeKeys          the japicmp {@code <exclude>} entries currently in {@code pom.xml}
     * @param classesInBuildOutput fully-qualified class names present in the current build output
     *                             (e.g. {@code target/classes}), exact string form including any
     *                             nested-class {@code $} separator
     * @return violation messages, empty when the invariant holds
     */
    public static List<String> evaluate(Set<RegistryKey> excludeKeys, Set<String> classesInBuildOutput) {
        Objects.requireNonNull(excludeKeys, "excludeKeys");
        Objects.requireNonNull(classesInBuildOutput, "classesInBuildOutput");

        List<String> violations = new ArrayList<>();
        for (RegistryKey key : new TreeSet<>(excludeKeys)) {
            if (!key.isClassLevel()) {
                continue;
            }
            String className = key.getClassName();
            if (classesInBuildOutput.contains(className)) {
                violations.add("japicmp <exclude>" + className + "</exclude> is a bare fully-qualified-"
                        + "name (whole-class) entry, but the class is still present in the build output - "
                        + "this silences the class's entire surviving public API, not just the change that "
                        + "prompted the entry. Name the individual removed/changed members instead "
                        + "(Class#member(paramTypes) or Class#field), or remove the entry if it is no "
                        + "longer needed at all.");
            }
        }
        return violations;
    }
}
