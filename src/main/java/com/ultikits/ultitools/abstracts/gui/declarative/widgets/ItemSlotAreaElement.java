package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderNode;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderObjectElement;
import com.ultikits.ultitools.ui.SlotBinding;
import org.jetbrains.annotations.NotNull;

/** Produces row-major binding leaves without reading or writing container contents. @since 7.0.0 */
public class ItemSlotAreaElement extends RenderObjectElement {
    /** @param widget the area description */
    public ItemSlotAreaElement(@NotNull ItemSlotArea widget) { super(widget); }

    @Override protected @NotNull RenderNode createRenderNode() {
        RenderNode node = new RenderNode(getWidget().getKey(), -1);
        updateRenderNode(node);
        return node;
    }

    @Override protected void updateRenderNode(@NotNull RenderNode renderNode) {
        ItemSlotArea widget = (ItemSlotArea) getWidget();
        renderNode.clearChildren();
        int visibleCount = Math.min(widget.getCount(), widget.getContainer().size() - widget.getFirstIndex());
        for (int offset = 0; offset < visibleCount; offset++) {
            RenderNode leaf = new RenderNode(null, widget.getStartSlot() + offset);
            leaf.setBinding(new SlotBinding(widget.getContainer(), widget.getFirstIndex() + offset, widget.getMode()));
            renderNode.addChild(leaf);
        }
    }
}
