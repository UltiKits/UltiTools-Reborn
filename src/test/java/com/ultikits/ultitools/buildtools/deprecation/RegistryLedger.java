package com.ultikits.ultitools.buildtools.deprecation;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * RED-phase stub. See the accompanying test for the required contract; the real implementation
 * lands in the GREEN commit.
 */
public final class RegistryLedger {

    public static RegistryLedger empty() {
        throw new UnsupportedOperationException("not implemented yet (RED phase)");
    }

    public static RegistryLedger of(List<DeprecationEntry> entries) {
        throw new UnsupportedOperationException("not implemented yet (RED phase)");
    }

    public static RegistryLedger merge(RegistryLedger prior, List<DeprecationEntry> freshScan, Set<RegistryKey> japicmpRemovedKeys) {
        throw new UnsupportedOperationException("not implemented yet (RED phase)");
    }

    public List<DeprecationEntry> entries() {
        return Collections.emptyList();
    }

    public String toJson() {
        throw new UnsupportedOperationException("not implemented yet (RED phase)");
    }

    public String toMarkdown() {
        throw new UnsupportedOperationException("not implemented yet (RED phase)");
    }
}
