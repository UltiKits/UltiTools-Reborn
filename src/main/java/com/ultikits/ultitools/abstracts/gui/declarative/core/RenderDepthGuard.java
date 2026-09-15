package com.ultikits.ultitools.abstracts.gui.declarative.core;

/**
 * Shared depth ceiling for the declarative render frame's traversal family (T-05-62 / #371).
 * <p>
 * Four recursions walk the Element tree within a single frame:
 * <ul>
 *   <li>{@code GuiRenderer.rebuildElement} -- the element-rebuild recursion, first in the frame</li>
 *   <li>{@code GridViewElement.performRebuild} -- nested inside the above for a GridView's own
 *       children</li>
 *   <li>{@code GridViewElement.collectRenderNodeLeaves} -- walks a GridView cell's subtree to
 *       propagate its computed slot to every leaf</li>
 *   <li>{@code GuiRenderer.collectRenderNodesRecursive} -- the render-node collection recursion,
 *       last in the frame</li>
 * </ul>
 * A module-authored widget tree deep enough overflows the JVM stack mid-frame;
 * {@link StackOverflowError} is an {@link Error}, so nothing on the render path catches it and it
 * escapes the scheduled frame, potentially leaving the Inventory half-written. Each of the four
 * call sites checks its current depth against {@link #MAX_DEPTH} before descending further,
 * raising {@link RenderDepthExceededException} -- a named, attributable failure -- instead.
 * <p>
 * <b>Derivation of {@link #MAX_DEPTH}.</b> Every widget tree shipped in this codebase -- the
 * {@code ShopPage} example in the framework's own documentation, and every fixture across
 * {@code GridViewTest}/{@code ContainerTest}/{@code ElementReconciliationTest} -- nests at most
 * 3-4 real levels deep: a root {@code Container} wrapping a {@code GridView} or a second
 * {@code Container}, wrapping a leaf {@code ItemDisplay}/{@code TextButton}, occasionally with one
 * extra {@code StatefulWidget}/{@code StatelessWidget} composition layer. {@code MAX_DEPTH} is set
 * to 64: roughly 16x that observed depth, generous headroom for legitimate composition this
 * codebase does not currently exercise (nested {@code Navigator} routes, deeply composed stateful
 * widgets), while remaining far below the point where these lightweight, few-local-variable
 * recursive methods would exhaust the JVM's default thread stack (in practice, comfortably in the
 * thousands of frames for a call shape this simple, on the stack sizes Paper's main thread runs
 * with). It is a sanity ceiling on module-authored tree shape, not an attempt to find the exact
 * point of stack exhaustion -- the guard is meant to trip long before the JVM ever would.
 *
 * @since 6.3.0
 */
public final class RenderDepthGuard {

    /**
     * The maximum Element-tree depth any of the four render-frame recursions may reach before
     * {@link #check(String, int)} raises {@link RenderDepthExceededException}. See the class
     * javadoc for the derivation.
     */
    public static final int MAX_DEPTH = 64;

    private RenderDepthGuard() {
        // Utility class -- no instances.
    }

    /**
     * Raises {@link RenderDepthExceededException} if {@code depth} exceeds {@link #MAX_DEPTH}.
     * A tree exactly at the limit is allowed through -- only depths strictly greater trip the
     * guard.
     *
     * @param guardName a name identifying which of the four recursions is checking, surfaced in
     *                  the exception message so a trip is attributable
     * @param depth     the current Element-tree depth, as computed by {@link #depthOf(Element)}
     */
    public static void check(String guardName, int depth) {
        if (depth > MAX_DEPTH) {
            throw new RenderDepthExceededException(guardName, depth, MAX_DEPTH);
        }
    }

    /**
     * Computes an Element's depth by walking its {@link Element#getParent()} chain -- an
     * iterative loop, not recursion, so computing the depth itself can never contribute to the
     * stack exhaustion this guard exists to prevent. The mounted root (no parent) is depth 0.
     *
     * @param element the Element whose depth to compute
     * @return the number of ancestors between {@code element} and the mounted root
     */
    public static int depthOf(Element element) {
        int depth = 0;
        Element current = element.getParent();
        while (current != null) {
            depth++;
            current = current.getParent();
        }
        return depth;
    }
}
