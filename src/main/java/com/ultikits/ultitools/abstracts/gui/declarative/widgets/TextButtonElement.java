package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderNode;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderObjectElement;
import com.ultikits.ultitools.utils.XVersionUtils;
import com.ultikits.ultitools.entities.Colors;
import mc.obliviate.inventory.Icon;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * TextButton 对应的 RenderObjectElement。
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class TextButtonElement extends RenderObjectElement {

    public TextButtonElement(@NotNull TextButton widget) {
        super(widget);
    }

    @Override
    @NotNull
    protected RenderNode createRenderNode() {
        TextButton widget = (TextButton) getWidget();
        
        // 创建玻璃板按钮
        ItemStack glass = createGlassPane(widget.getColor());
        Icon icon = new Icon(glass);
        icon.setName(widget.getText());
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
        TextButton widget = (TextButton) getWidget();

        // 更新位置
        renderNode.setSlotIndex(widget.getSlot());

        // 更新 Icon
        ItemStack glass = createGlassPane(widget.getColor());
        Icon icon = new Icon(glass);
        icon.setName(widget.getText());
        if (widget.getLore() != null) {
            icon.setLore(widget.getLore());
        }
        renderNode.setIcon(icon);

        // 更新点击处理器
        if (widget.getClickHandler() != null) {
            renderNode.setClickHandler(widget.getClickHandler());
        }
    }

    @NotNull
    private ItemStack createGlassPane(@NotNull String color) {
        // 使用 XSeries 获取彩色玻璃板
        try {
            // 尝试获取彩色玻璃板
            return XVersionUtils.getColoredPlaneGlass(
                    Colors.valueOf(color.toUpperCase())
            );
        } catch (Exception e) {
            // 回退到普通玻璃板
            return new ItemStack(Material.GLASS_PANE);
        }
    }
}
