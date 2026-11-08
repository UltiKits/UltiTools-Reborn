package com.ultikits.ultitools;

/** A failed transformation is a named linkage failure, never an optional missing dependency. */
final class ModuleCompatibilityException extends LinkageError {
    private static final long serialVersionUID = 1L;

    ModuleCompatibilityException(String binaryName, ClassRewriteRule rule, Throwable cause) {
        super("Failed to rewrite module class " + binaryName + " with " + rule.getClass().getName());
        initCause(cause);
    }
}
