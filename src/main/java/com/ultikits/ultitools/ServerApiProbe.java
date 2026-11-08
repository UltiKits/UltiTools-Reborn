package com.ultikits.ultitools;

/**
 * Internal server-API facts shared by rewriting and the later linkage audit. Owners use JVM
 * internal names; member descriptors are exact JVM descriptors, including method return types.
 * Implementations use the framework's parent loader, never initialize types, and are thread-safe.
 * A linkage failure is not an absent type and must not silently become ABSENT or false.
 */
interface ServerApiProbe {
    ServerApiTypeKind typeKind(String owner);

    /** Resolves the exact member, including inherited members according to JVM lookup rules. */
    boolean hasMethod(String owner, String name, String descriptor);

    boolean hasField(String owner, String name, String descriptor);
}

/** Kept outside the interface: nested interface types are implicitly public. */
enum ServerApiTypeKind {
    ABSENT, CLASS, INTERFACE
}
