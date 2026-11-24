package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderNode;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderObjectElement;
import com.ultikits.ultitools.ui.GridItem;
import org.jetbrains.annotations.NotNull;

/** The render element for an {@link ItemDisplay}. @since 6.2.0 */
public class ItemDisplayElement extends RenderObjectElement {
    /** @param widget the item display */
    public ItemDisplayElement(@NotNull ItemDisplay widget) { super(widget); }

    @Override protected @NotNull RenderNode createRenderNode() {
        ItemDisplay widget = (ItemDisplay) getWidget();
        return new RenderNode(widget.getKey(), widget.getSlot(), item(widget));
    }

    @Override protected void updateRenderNode(@NotNull RenderNode renderNode) {
        ItemDisplay widget = (ItemDisplay) getWidget();
        renderNode.setSlotIndex(widget.getSlot());
        renderNode.setItem(item(widget));
    }

    private static GridItem item(ItemDisplay widget) {
        return widget.getClickHandler() == null ? GridItem.of(widget.getIcon())
                : GridItem.of(widget.getIcon(), widget.getClickHandler());
    }
}
