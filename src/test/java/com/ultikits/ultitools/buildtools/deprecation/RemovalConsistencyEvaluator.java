package com.ultikits.ultitools.buildtools.deprecation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The D-01 / D-21 / D-22 decision logic: a japicmp {@code <exclude>} entry whose key has no
 * corresponding registry entry and no admissible allowlist justification turns the build red.
 *
 * <p>Pure logic over three already-parsed inputs - the pom's {@code <exclude>} key list, the
 * parsed japicmp report ({@link JapicmpReportReader.Report}), and the registry ledger
 * ({@link RegistryLedger}) - so every behaviour below is unit-testable without a Maven run.
 *
 * <p><strong>Structural scope, load-bearing for the checks below (see
 * {@link JapicmpReportReader}'s own javadoc for the underlying mechanism):</strong> once a symbol
 * is named in {@code <excludes>}, japicmp filters it out of its own report entirely - a
 * currently-excluded entry can therefore never be re-confirmed against a fresh report run with
 * that same exclude list still in place. Two consequences follow directly, both deliberate:
 * <ul>
 *   <li>The {@link Finding.Kind#STALE_EXCLUSION} check never fires for a whole-class exclude
 *       (its class element is entirely absent from the report by japicmp's own class-filter
 *       mechanism, with zero recoverable signal); it applies only to member-level entries, using
 *       the member's <em>enclosing class</em> still being a real, comparison-visible class as the
 *       corroborating signal that the exclude key refers to something real rather than a typo.</li>
 *   <li>The {@link Finding.Kind#INADMISSIBLE_ALLOWLIST} check can only re-verify a member's
 *       old-side modifier when that exact member is <em>still</em> visible in the report - i.e.
 *       for a symbol not (yet) excluded, or a new candidate exclude being evaluated before its
 *       {@code <exclude>} line is added. An already-excluded allowlist entry's original
 *       admissibility was established once, by hand, at authoring time (see
 *       {@code 07-JAPICMP-BASELINE.md}) - this evaluator cannot and does not re-derive it from a
 *       report that no longer shows the member at all.</li>
 * </ul>
 */
public final class RemovalConsistencyEvaluator {

    /** The framework's declared comparison scope (D-06): public + protected. */
    public static final String EXPECTED_SCOPE = "PROTECTED";

    private RemovalConsistencyEvaluator() {
    }

    /**
     * D-21's entire admissibility test: a bytecode fact from the report's own
     * {@code <modifier oldValue>}, nothing else. No override parameter exists anywhere in this
     * evaluator's public API that could suppress a rejection.
     */
    public static boolean isAdmissibleAllowlistEntry(JapicmpReportReader.Entry entry) {
        String modifier = entry.getOldAccessModifier();
        return "PRIVATE".equals(modifier) || "PACKAGE_PROTECTED".equals(modifier);
    }

    // PMD.NPathComplexity: each `if` in this method is one named D-01/D-21/D-22 consistency
    // rule, applied independently to every key. Read top to bottom, the method IS the rule
    // list -- which is what makes a build gate auditable. Splitting it into per-rule helpers
    // would lower the number while removing the property that the rules can be read in order.
    @SuppressWarnings("PMD.NPathComplexity")
    public static List<Finding> evaluate(Set<RegistryKey> excludeKeys, JapicmpReportReader.Report report,
            RegistryLedger registry) {
        List<Finding> findings = new ArrayList<>();

        // D-22 scope equality: a report narrower than the framework's declared scope can never
        // confirm a removal in that scope - nothing else here is trustworthy if it disagrees.
        String reportScope = report.accessModifier();
        if (reportScope != null && !EXPECTED_SCOPE.equalsIgnoreCase(reportScope)) {
            findings.add(Finding.scopeMismatch(reportScope));
            return findings;
        }

        Map<String, DeprecationEntry> registryByKey = indexByKeyString(registry);

        // D-01 staleness: a member-level exclude key with no registry entry AND no visible trace
        // in the report - neither the exact key nor its enclosing class - protects nothing
        // discoverable. Whole-class excludes are exempt (see class javadoc).
        for (RegistryKey key : excludeKeys) {
            if (key.isClassLevel()) {
                continue;
            }
            boolean inRegistry = registryByKey.containsKey(key.toString());
            boolean keyVisible = report.entries().containsKey(key);
            boolean enclosingClassVisible = report.entries().containsKey(RegistryKey.forClass(key.getClassName()));
            if (!inRegistry && !keyVisible && !enclosingClassVisible) {
                findings.add(Finding.staleExclusion(key));
            }
        }

        // D-01 coverage: a report REMOVED key with no exclude entry and no registry entry is an
        // unrecorded removal.
        for (Map.Entry<RegistryKey, JapicmpReportReader.Entry> e : report.entries().entrySet()) {
            if (!"REMOVED".equals(e.getValue().getChangeStatus())) {
                continue;
            }
            RegistryKey key = e.getKey();
            if (!excludeKeys.contains(key) && !registryByKey.containsKey(key.toString())) {
                findings.add(Finding.unrecordedRemoval(key));
            }
        }

        // D-21 admissibility: an exclude key that is still visible in the report (see class
        // javadoc for when that is possible) and NOT tracked by the deprecation lifecycle must
        // clear the mechanical PRIVATE/PACKAGE_PROTECTED old-side test.
        for (RegistryKey key : excludeKeys) {
            JapicmpReportReader.Entry entry = report.entries().get(key);
            if (entry == null || registryByKey.containsKey(key.toString())) {
                continue;
            }
            if (!isAdmissibleAllowlistEntry(entry)) {
                findings.add(Finding.inadmissibleAllowlist(key, entry.getOldAccessModifier()));
            }
        }

        // D-22 pom-sync: every REMOVED registry entry must have a matching pom exclude, or
        // japicmp will re-flag it against the old baseline on every subsequent build with no
        // record of why it is accepted.
        for (DeprecationEntry entry : registry.entries()) {
            if (entry.getStatus() == DeprecationEntry.Status.REMOVED && !excludeKeys.contains(entry.getKey())) {
                findings.add(Finding.missingExclusionForRemoved(entry.getKey()));
            }
        }

        return findings;
    }

    private static Map<String, DeprecationEntry> indexByKeyString(RegistryLedger registry) {
        Map<String, DeprecationEntry> byKey = new HashMap<>();
        for (DeprecationEntry entry : registry.entries()) {
            byKey.put(entry.getKey().toString(), entry);
        }
        return byKey;
    }

    /** One consistency violation found by {@link #evaluate}. */
    public static final class Finding {

        /** What kind of consistency violation this finding represents. */
        public enum Kind {
            SCOPE_MISMATCH, STALE_EXCLUSION, UNRECORDED_REMOVAL, INADMISSIBLE_ALLOWLIST, MISSING_EXCLUSION_FOR_REMOVED
        }

        private final Kind kind;
        private final RegistryKey key;
        private final String message;

        private Finding(Kind kind, RegistryKey key, String message) {
            this.kind = kind;
            this.key = key;
            this.message = message;
        }

        static Finding scopeMismatch(String actualScope) {
            return new Finding(Kind.SCOPE_MISMATCH, null,
                    "japicmp report scope '" + actualScope + "' is narrower than the framework's "
                            + "declared scope '" + EXPECTED_SCOPE + "' (D-06) - a removal in that "
                            + "scope could never be confirmed against this report");
        }

        static Finding staleExclusion(RegistryKey key) {
            return new Finding(Kind.STALE_EXCLUSION, key,
                    "pom <exclude> entry '" + key + "' has no registry entry and no trace in the "
                            + "japicmp report - it may be stale, or a typo that never matched "
                            + "anything real");
        }

        static Finding unrecordedRemoval(RegistryKey key) {
            return new Finding(Kind.UNRECORDED_REMOVAL, key,
                    "japicmp reports '" + key + "' REMOVED, but it has no pom <exclude> entry and "
                            + "no registry entry - an unrecorded removal");
        }

        static Finding inadmissibleAllowlist(RegistryKey key, String oldModifier) {
            return new Finding(Kind.INADMISSIBLE_ALLOWLIST, key,
                    "pom <exclude> entry '" + key + "' is not tracked by the deprecation registry "
                            + "and its japicmp old-side access modifier is '" + oldModifier + "', "
                            + "not PRIVATE or PACKAGE_PROTECTED - D-21 rejects it unconditionally");
        }

        static Finding missingExclusionForRemoved(RegistryKey key) {
            return new Finding(Kind.MISSING_EXCLUSION_FOR_REMOVED, key,
                    "registry entry '" + key + "' has status REMOVED but no matching pom "
                            + "<exclude> entry - japicmp will re-flag it against the old baseline "
                            + "with no accepted record");
        }

        public Kind getKind() {
            return kind;
        }

        public RegistryKey getKey() {
            return key;
        }

        public String describe() {
            return message;
        }

        @Override
        public String toString() {
            return "[" + kind + "] " + message;
        }
    }
}
