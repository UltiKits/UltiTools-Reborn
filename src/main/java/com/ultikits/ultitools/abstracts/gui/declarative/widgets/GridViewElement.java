package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.Element;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderDepthGuard;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderNode;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderObjectElement;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * The Element counterpart of GridView.
 * <p>
 * Task 2 (05-13, WIRE-03 / D-11): positions ANY widget type by writing parent data at render
 * time -- {@link #applyGridPositions()} computes each child's slot from the {@link GridView}'s
 * own layout parameters and writes it onto the {@link RenderNode}(s) that child's subtree
 * produced, mirroring Flutter's {@code ParentDataWidget}: the child never knows its own
 * position. This replaces the pre-plan approach of {@code GridView.Builder.items()}
 * special-casing {@code ItemDisplay} and hand-copying six fields at build time -- every other
 * widget type used to receive no slot at all and stacked on the default.
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class GridViewElement extends Element {

    private static final Logger LOGGER = Logger.getLogger(GridViewElement.class.getName());

    /**
     * The slot value {@link ItemDisplay} and {@link TextButton}'s builders default to when the
     * caller never calls {@code .slot(...)}. Neither widget carries a separate "unset" flag --
     * 0 doubles as both "not specified" and "genuinely slot 0" in this codebase today -- so
     * this is the only signal available here for D-11's conflict rule without changing
     * {@code Widget}/{@code ItemDisplay}/{@code TextButton}'s public API (out of this plan's
     * scope; {@code Widget} in particular is required to stay unchanged). A widget legitimately
     * positioned at slot 0 therefore never triggers the conflict warning below -- see
     * 05-13-SUMMARY.md for the full reasoning behind this choice.
     */
    private static final int DEFAULT_UNSET_SLOT = 0;

    private final List<Element> childElements = new ArrayList<>();

    public GridViewElement(@NotNull GridView<?> widget) {
        super(widget);
    }

    @Override
    public void mount(@Nullable Element parent) {
        super.mount(parent);
        mountChildren();
        // A freshly-mounted Element's own dirty flag is cleared by Element.mount() before this
        // runs, so performRebuild() (below) will not fire on the very first frame -- exactly
        // like a leaf RenderObjectElement's RenderNode, which is created lazily on first
        // access rather than through performRebuild(). Applying positions here too closes that
        // gap for the first frame; performRebuild() covers every frame after.
        applyGridPositions();
    }

    @Override
    public void update(@NotNull Widget newWidget) {
        super.update(newWidget);
        GridView<?> gridView = (GridView<?>) getWidget();
        updateChildren(childElements, gridView.getChildren());
    }

    @Override
    public void performRebuild() {
        // Guarded by RenderDepthGuard (T-05-62 / #371): this GridViewElement's OWN
        // Element-tree depth, not the child loop's index -- a tree deep enough (through this
        // GridView, or through a deeper GridView/Container ancestor) raises
        // RenderDepthExceededException here rather than overflowing the JVM stack mid-frame.
        RenderDepthGuard.check("GridViewElement.performRebuild", RenderDepthGuard.depthOf(this));

        // Rebuild each child's COMPLETE dirty subtree first, then write parent data. A component
        // cell (a StatelessWidget/StatefulWidget wrapper, at any nesting depth) only updates its
        // leaf and marks it dirty when the cell itself rebuilds; the leaf's own rebuild resets
        // its RenderNode slot from the widget. If positions were written before that leaf
        // rebuild, GuiRenderer's later pre-order visit would rebuild the leaf and overwrite the
        // computed slot, so on every frame after the first the cells would collapse onto the
        // widget's default slot and click handlers would be keyed by the wrong slot. Walking the
        // whole subtree here leaves every descendant clean before applyGridPositions() runs, so
        // nothing later in this frame can overwrite what it writes.
        for (Element child : childElements) {
            rebuildSubtree(child);
        }
        applyGridPositions();
        clearDirty();
    }

    /**
     * Rebuilds {@code element}'s complete dirty subtree in {@code GuiRenderer.rebuildElement}'s
     * order: rebuild the node if it is dirty, then descend into its children as they are after
     * that rebuild.
     * <p>
     * Guarded by {@link RenderDepthGuard} at every visited node, under the same guard name as
     * {@link #performRebuild()}: a module-authored cell subtree deep enough raises
     * {@code RenderDepthExceededException} here rather than overflowing the JVM stack mid-frame.
     *
     * @param element the root of the subtree to rebuild
     */
    private static void rebuildSubtree(@NotNull Element element) {
        RenderDepthGuard.check("GridViewElement.performRebuild", RenderDepthGuard.depthOf(element));

        if (element.isDirty()) {
            element.performRebuild();
        }
        for (Element child : element.getChildren()) {
            rebuildSubtree(child);
        }
    }

    @Override
    public void unmount() {
        for (Element child : childElements) {
            child.unmount();
        }
        childElements.clear();
        super.unmount();
    }

    @Override
    @NotNull
    public List<Element> getChildren() {
        return Collections.unmodifiableList(childElements);
    }

    private void mountChildren() {
        // First mount shares reconciliation's creation path (starting from the still-empty
        // list): a child whose mount throws is cleaned up immediately, and every child mounted
        // before it stays owned, so the parent's own cleanup can release it.
        GridView<?> gridView = (GridView<?>) getWidget();
        updateChildren(childElements, gridView.getChildren());
    }

    /**
     * Writes each child's GridView-computed slot onto the RenderNode(s) its subtree produced.
     * <p>
     * This is parent data, D-11's mechanism: the child never knows its own position, the
     * GridView writes it after the child's complete subtree has (re)built and before
     * {@code GuiRenderer}'s collection walk snapshots the tree -- 05-11's
     * {@code collectRenderNodesRecursive} copies via {@code RenderNode.copy()}, so a write after
     * that point would be invisible in the collected snapshot. On every frame after the first,
     * {@link #performRebuild()} rebuilds every dirty descendant of each child (not only the
     * direct children) before calling this, so no later leaf rebuild in the same frame can reset
     * a leaf's slot from its widget and overwrite the value written here. On the first frame,
     * {@link #mount} calls this after each child's own mount-time build.
     * <p>
     * A child whose Element is not itself a leaf (a nested {@link Container}, for example) has
     * no {@link RenderNode} of its own; {@link #collectRenderNodeLeaves} walks its descendants
     * and every leaf found receives the same computed slot. When more than one leaf exists
     * under a single cell this is a genuine, documented collision (see
     * {@code DEFAULT_UNSET_SLOT}'s sibling note in 05-13-SUMMARY.md) rather than an arbitrary
     * "last writer wins" -- the traversal order is deterministic post-order, matching
     * {@code GuiRenderer.collectRenderNodesRecursive}'s own discipline, so the result is
     * reproducible across rebuilds even though it is not yet a resolved layout.
     */
    private void applyGridPositions() {
        GridView<?> gridView = (GridView<?>) getWidget();
        List<RenderNode> leaves = new ArrayList<>();
        for (int i = 0; i < childElements.size(); i++) {
            int computedSlot = gridView.calculateSlotForChild(i);
            leaves.clear();
            collectRenderNodeLeaves(childElements.get(i), leaves);
            for (RenderNode leaf : leaves) {
                if (leaf.getSlotIndex() != DEFAULT_UNSET_SLOT) {
                    LOGGER.warning("GridView overrides an explicit slot (" + leaf.getSlotIndex()
                            + ") on the child at index " + i + "; the GridView-computed slot ("
                            + computedSlot + ") wins. Remove the child widget's own .slot(...) "
                            + "call to silence this warning.");
                }
                leaf.setSlotIndex(computedSlot);
            }
        }
    }

    private static void collectRenderNodeLeaves(@NotNull Element element, @NotNull List<RenderNode> out) {
        // Guarded by RenderDepthGuard (T-05-62 / #371) -- see performRebuild's javadoc above.
        // This is the walk the original finding names as the newly-added, unbounded one (#3 of
        // the four): a nested Container inside a single GridView cell has no ceiling here today.
        RenderDepthGuard.check("GridViewElement.collectRenderNodeLeaves", RenderDepthGuard.depthOf(element));

        if (element instanceof RenderObjectElement) {
            out.add(((RenderObjectElement) element).getRenderNode());
        }
        for (Element child : element.getChildren()) {
            collectRenderNodeLeaves(child, out);
        }
    }
}
