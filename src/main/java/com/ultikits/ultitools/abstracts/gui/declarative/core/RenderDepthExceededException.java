package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.ApiStatus;

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
 * handled like any other failure in the frame. "Attributable" describes what a reader of the
 * server console or the UltiPanel error dashboard sees (the guard name and depth reached in the
 * message, and one report per guard site rather than one per frame -- see
 * {@code GuiScheduler.executeFrame}'s routing into {@code ErrorReportCollector}, WR-01); it does
 * not claim the exception is meant to be caught and recovered from by module authors.
 * <p>
 * <b>WR-03:</b> {@code @ApiStatus.Internal} -- purely internal render-engine machinery; no
 * module-author use case for catching this exception is intended, and this release adds no new
 * public surface.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
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
