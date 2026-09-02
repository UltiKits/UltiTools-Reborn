package com.ultikits.ultitools.utils;

import java.util.Comparator;

/**
 * Semantic version comparison utility class.
 * <p>
 * The full ordering contract, since several build-gate checks in this repository depend on it:
 * <ol>
 *   <li>A leading version-prefix letter ({@code v} or {@code V}) is stripped before comparison.</li>
 *   <li>The remaining string is split into segments on both {@code .} and {@code -}.</li>
 *   <li>When both segments at a position parse as integers, they compare numerically.</li>
 *   <li>When exactly one segment parses as an integer, the numeric segment sorts higher (a numeric
 *   segment is always considered newer than a non-numeric one at the same position). When neither
 *   parses as an integer, they fall back first to a pre-release priority ordering
 *   ({@code alpha < beta < rc < SNAPSHOT < a plain release}), and only if that priority is equal do
 *   they compare lexicographically, case-insensitively.</li>
 *   <li>A missing trailing segment (the shorter version ran out of segments) is treated as {@code 0}
 *   for that position, so {@code 1.2} and {@code 1.2.0} compare equal.</li>
 * </ol>
 * The pre-release fallback is what ranks a snapshot below the plain release of the same numeric
 * core (e.g. {@code 1.2.0-SNAPSHOT} sorts below {@code 1.2.0}), since a non-numeric segment never
 * outranks a numeric one and {@code SNAPSHOT}'s own priority (4) sits below a plain release's (5).
 *
 * @author wisdomme
 * @since 6.2.0
 */
public final class VersionComparatorUtil {

    /**
     * Singleton version comparator.
     */
    public static final Comparator<String> COMPARATOR = VersionComparatorUtil::compare;

    private VersionComparatorUtil() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Compares two version strings.
     *
     * @param v1 the first version
     * @param v2 the second version
     * @return negative if v1 &lt; v2, 0 if v1 == v2, positive if v1 &gt; v2
     */
    public static int compare(String v1, String v2) {
        if (v1 == null) v1 = "";
        if (v2 == null) v2 = "";

        // Strip a leading v or V.
        v1 = stripPrefix(v1);
        v2 = stripPrefix(v2);

        // Split the version into segments.
        String[] parts1 = v1.split("[.\\-]");
        String[] parts2 = v2.split("[.\\-]");

        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            String p1 = i < parts1.length ? parts1[i] : "0";
            String p2 = i < parts2.length ? parts2[i] : "0";

            int cmp = compareSegment(p1, p2);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /**
     * Returns whether v1 is less than v2.
     */
    public static boolean isLessThan(String v1, String v2) {
        return compare(v1, v2) < 0;
    }

    /**
     * Returns whether v1 is greater than v2.
     */
    public static boolean isGreaterThan(String v1, String v2) {
        return compare(v1, v2) > 0;
    }

    /**
     * Returns whether v1 is equal to v2.
     */
    public static boolean isEqual(String v1, String v2) {
        return compare(v1, v2) == 0;
    }

    /**
     * Strips the version prefix.
     */
    private static String stripPrefix(String version) {
        if (version.startsWith("v") || version.startsWith("V")) {
            return version.substring(1);
        }
        return version;
    }

    /**
     * Compares a single version segment.
     */
    private static int compareSegment(String s1, String s2) {
        // Try comparing as numbers.
        Integer n1 = parseIntOrNull(s1);
        Integer n2 = parseIntOrNull(s2);

        if (n1 != null && n2 != null) {
            return n1.compareTo(n2);
        }

        // A numeric segment outranks a non-numeric one.
        if (n1 != null) return 1;
        if (n2 != null) return -1;

        // Pre-release priority.
        int p1 = getPreReleasePriority(s1);
        int p2 = getPreReleasePriority(s2);

        if (p1 != p2) {
            return p1 - p2;
        }

        // Lexicographic comparison.
        return s1.compareToIgnoreCase(s2);
    }

    /**
     * Parses an integer, returning null on failure.
     */
    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Gets the pre-release priority.
     * <p>
     * alpha &lt; beta &lt; rc &lt; SNAPSHOT &lt; (release)
     */
    private static int getPreReleasePriority(String segment) {
        String lower = segment.toLowerCase();
        if (lower.equals("alpha") || lower.startsWith("alpha")) return 1;
        if (lower.equals("beta") || lower.startsWith("beta")) return 2;
        if (lower.equals("rc") || lower.startsWith("rc")) return 3;
        if (lower.equals("snapshot")) return 4;
        return 5; // plain release
    }
}
