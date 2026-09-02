package com.ultikits.ultitools.buildtools.deprecation;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * D-03 stub - RED phase placeholder, deliberately wrong. Implemented for real in the GREEN commit
 * that follows.
 */
public final class ReleaseBoundaryInvariant {

    private ReleaseBoundaryInvariant() {
    }

    public static List<String> evaluate(String projectVersion, String baselineVersion, Set<RegistryKey> excludeKeys) {
        return Collections.emptyList();
    }
}
