package com.ultikits.ultitools;

/**
 * An internal, ordered transformation of a module's class bytes. Implementations must be safe to
 * invoke concurrently for different classes. No rule is installed by the foundation loader.
 */
interface ClassRewriteRule {
    /** Inspects the current bytes without changing them or initializing any module class. */
    boolean wouldChange(String binaryName, byte[] bytes) throws Exception;

    /** Returns the replacement bytes; never mutates the input and never returns null. */
    byte[] rewrite(String binaryName, byte[] bytes) throws Exception;

    /**
     * Whether this rule actually repairs a particular reference for the running server. A rule
     * must not claim to cover a missing member merely because it can align the owner's type kind.
     */
    boolean covers(ServerApiReference reference);
}
