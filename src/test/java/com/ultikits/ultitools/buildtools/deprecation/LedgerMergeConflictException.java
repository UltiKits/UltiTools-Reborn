package com.ultikits.ultitools.buildtools.deprecation;

import java.util.Collections;
import java.util.List;

/**
 * Thrown by {@link RegistryLedger#merge} when the prior ledger, the fresh source scan, and the
 * japicmp report disagree about whether a member has been removed (D-22). Nothing may flip to
 * {@code REMOVED} on the evidence of only one source; the caller (the generator's {@code main()})
 * turns this into a non-zero exit, failing {@code mvn verify}.
 */
public final class LedgerMergeConflictException extends RuntimeException {

    private final List<String> conflicts;

    public LedgerMergeConflictException(List<String> conflicts) {
        super(buildMessage(conflicts));
        this.conflicts = Collections.unmodifiableList(conflicts);
    }

    public List<String> getConflicts() {
        return conflicts;
    }

    private static String buildMessage(List<String> conflicts) {
        StringBuilder sb = new StringBuilder("Deprecation registry / japicmp disagreement (D-22) - refusing to guess:\n");
        for (String conflict : conflicts) {
            sb.append("  - ").append(conflict).append('\n');
        }
        return sb.toString();
    }
}
