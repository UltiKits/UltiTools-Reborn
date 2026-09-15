package com.ultikits.ultitools.abstracts.gui.declarative.core;

/**
 * Raised by {@link RenderDepthGuard} when one of the declarative render frame's traversal
 * recursions reaches an Element-tree depth beyond {@link RenderDepthGuard#MAX_DEPTH}.
 * <p>
 * A named, attributable failure in place of the {@link StackOverflowError} the guard exists to
 * prevent (T-05-62 / #371): a module-authored widget tree deep enough overflows the JVM stack
 * mid-frame, and {@code StackOverflowError} is an {@link Error} -- nothing on the render path
 * catches it, so it would otherwise escape the scheduled frame and can leave the Inventory
 * half-written. This exception, by contrast, is a plain {@link RuntimeException} raised by
 * ordinary application code before any stack exhaustion occurs, so it propagates and can be
 * handled like any other failure in the frame.
 *
 * @since 6.3.0
 */
public class RenderDepthExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception, naming the guard that tripped, the depth reached, and the
     * configured limit.
     *
     * @param guardName identifies which of the four render-frame recursions raised this --
     *                  surfaced in the message so the failure is attributable, not anonymous
     * @param depth     the Element-tree depth that was reached
     * @param maxDepth  the configured limit that was exceeded
     */
    public RenderDepthExceededException(String guardName, int depth, int maxDepth) {
        super(String.format(
                "%s: widget tree depth %d exceeds the render-frame depth limit (%d). "
                        + "The tree is too deep to render safely -- flatten it or split it "
                        + "across multiple pages/widgets.",
                guardName, depth, maxDepth));
    }
}
