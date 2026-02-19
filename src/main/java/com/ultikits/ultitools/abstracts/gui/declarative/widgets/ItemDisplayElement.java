package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderNode;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderObjectElement;
import mc.obliviate.inventory.Icon;
import org.jetbrains.annotations.NotNull;

/**
 * ItemDisplay 对应的 RenderObjectElement。
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
        
        // 创建 Icon
        Icon icon = new Icon(widget.getItemStack());
        if (widget.getDisplayName() != null) {
            icon.setName(widget.getDisplayName());
        }
        if (widget.getLore() != null) {
            icon.setLore(widget.getLore());
        }

        // 创建 RenderNode
        RenderNode node = new RenderNode(widget.getKey(), widget.getSlot(), icon);
        if (widget.getClickHandler() != null) {
            node.setClickHandler(widget.getClickHandler());
        }

        return node;
    }

    @Override
    protected void updateRenderNode(@NotNull RenderNode renderNode) {
        ItemDisplay widget = (ItemDisplay) getWidget();

        // 更新位置
        renderNode.setSlotIndex(widget.getSlot());

        // 更新 Icon
        Icon icon = new Icon(widget.getItemStack());
        if (widget.getDisplayName() != null) {
            icon.setName(widget.getDisplayName());
        }
        if (widget.getLore() != null) {
            icon.setLore(widget.getLore());
        }
        renderNode.setIcon(icon);

        // 更新点击处理器
        if (widget.getClickHandler() != null) {
            renderNode.setClickHandler(widget.getClickHandler());
        }
    }
}
