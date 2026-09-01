package com.ultikits.ultitools.buildtools.deprecation;

import java.util.List;

/**
 * RED-phase stub. See the accompanying test for the required contract; the real implementation
 * lands in the GREEN commit.
 */
public final class RegistryKey implements Comparable<RegistryKey> {

    public static RegistryKey forClass(String className) {
        throw new UnsupportedOperationException("not implemented yet (RED phase)");
    }

    public static RegistryKey forMember(String className, String memberName, List<String> parameterTypes) {
        throw new UnsupportedOperationException("not implemented yet (RED phase)");
    }

    public static RegistryKey forField(String className, String fieldName) {
        throw new UnsupportedOperationException("not implemented yet (RED phase)");
    }

    @Override
    public int compareTo(RegistryKey other) {
        throw new UnsupportedOperationException("not implemented yet (RED phase)");
    }
}
