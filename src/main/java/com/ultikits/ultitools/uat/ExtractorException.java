package com.ultikits.ultitools.uat;

/**
 * A fail-closed signal from the module surface extractor. Every path that would otherwise skip a
 * class or silently drop a row throws this instead, naming the offending class(es) and, when
 * known, the underlying cause.
 * <p>
 * The extractor's own contract (Phase 10, D-10-01) is that a class it cannot load is a named
 * error with its cause and a non-zero exit, never a silent skip, and that a row-id collision
 * between two distinct source elements is a named error, never a merge.
 *
 * @since 6.3.0
 */
public class ExtractorException extends Exception {

    private static final long serialVersionUID = 1L;

    public ExtractorException(String message) {
        super(message);
    }

    public ExtractorException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * A class under {@code --classes} could not be resolved. Names both the binary class name
     * the extractor was trying to load and the cause's own class name, per the extractor's
     * fail-closed contract.
     *
     * @param binaryClassName the binary name of the class that failed to load
     * @param cause           the underlying failure (e.g. {@link LinkageError})
     * @return a new {@link ExtractorException} naming both
     */
    public static ExtractorException classLoadFailure(String binaryClassName, Throwable cause) {
        return new ExtractorException(
                "Failed to load class " + binaryClassName + ": " + cause.getClass().getName()
                        + (cause.getMessage() != null ? ": " + cause.getMessage() : ""),
                cause);
    }

    /**
     * Two distinct source elements produced the same row id. Nothing merges; the build fails
     * closed, naming both fully qualified class names so the operator can rename or otherwise
     * disambiguate one of them.
     *
     * @param id                the colliding row id
     * @param existingClassName the fully qualified class name that first claimed {@code id}
     * @param newClassName      the fully qualified class name that collided with it
     * @return a new {@link ExtractorException} naming both
     */
    public static ExtractorException idCollision(String id, String existingClassName, String newClassName) {
        return new ExtractorException(
                "Row id collision " + id + " between " + existingClassName + " and " + newClassName);
    }

    /**
     * A class {@code --classes} enumerated resolved to bytecode from somewhere OTHER than the
     * requested {@code classesRoot} -- a standard {@link java.net.URLClassLoader} delegates to
     * its parent first, so a binary name also present on the caller's own {@code -cp} (a
     * previously installed version, or a sibling module's own copy of a shared class) is
     * resolved from there instead of the artifact under inspection (Codex review of PR #427).
     * Failing closed here, rather than silently accepting the wrong bytecode, is what prevents
     * a false-clean drift check or a surface describing a different build than the one named.
     *
     * @param binaryClassName the binary name of the class that resolved to the wrong origin
     * @param expected        {@code classesRoot}'s own URL
     * @param actual          the resolved class's actual code source location, or {@code null}
     *                        when it has none (e.g. no {@link java.security.CodeSource} at all)
     * @return a new {@link ExtractorException} naming both the class and both locations
     */
    public static ExtractorException codeSourceMismatch(String binaryClassName, String expected, String actual) {
        return new ExtractorException(
                "Class " + binaryClassName + " did not resolve from the requested classesRoot: "
                        + "expected " + expected + " but resolved from "
                        + (actual != null ? actual : "an unknown location (no CodeSource)") + ". "
                        + "A standard URLClassLoader delegates to its parent first, so this binary "
                        + "name also exists somewhere on the caller's own -cp -- refusing to "
                        + "extract from the wrong bytecode.");
    }
}
