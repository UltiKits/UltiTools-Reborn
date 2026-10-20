package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderNode;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderObjectElement;
import mc.obliviate.inventory.Icon;
import org.jetbrains.annotations.NotNull;

/**
 * The RenderObjectElement counterpart of ItemDisplay.
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class ItemDisplayElement extends RenderObjectElement {

    public ItemDisplayElement(@NotNull ItemDisplay widget) {
        super(widget);
    }

    @Override
    @NotNull
    protected RenderNode createRenderNode() {
        ItemDisplay widget = (ItemDisplay) getWidget();

        // Create the Icon
        Icon icon = new Icon(widget.getItemStack());
        if (widget.getDisplayName() != null) {
            icon.setName(widget.getDisplayName());
        }
        if (widget.getLore() != null) {
            icon.setLore(widget.getLore());
        }

        // Create the RenderNode
        RenderNode node = new RenderNode(widget.getKey(), widget.getSlot(), icon);
        if (widget.getClickHandler() != null) {
            node.setClickHandler(widget.getClickHandler());
        }

        return node;
    }

    @Override
    protected void updateRenderNode(@NotNull RenderNode renderNode) {
        ItemDisplay widget = (ItemDisplay) getWidget();

        // Update the position
        renderNode.setSlotIndex(widget.getSlot());

        // Update the Icon
        Icon icon = new Icon(widget.getItemStack());
        if (widget.getDisplayName() != null) {
            icon.setName(widget.getDisplayName());
        }
        if (widget.getLore() != null) {
            icon.setLore(widget.getLore());
        }
        renderNode.setIcon(icon);

        // Update the click handler
        if (widget.getClickHandler() != null) {
            renderNode.setClickHandler(widget.getClickHandler());
        }
    }
}
