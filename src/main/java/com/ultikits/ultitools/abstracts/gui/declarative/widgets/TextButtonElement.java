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
 * The RenderObjectElement counterpart of TextButton.
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

        // Create the glass-pane button
        ItemStack glass = createGlassPane(widget.getColor());
        Icon icon = new Icon(glass);
        icon.setName(widget.getText());
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
        TextButton widget = (TextButton) getWidget();

        // Update the position
        renderNode.setSlotIndex(widget.getSlot());

        // Update the Icon
        ItemStack glass = createGlassPane(widget.getColor());
        Icon icon = new Icon(glass);
        icon.setName(widget.getText());
        if (widget.getLore() != null) {
            icon.setLore(widget.getLore());
        }
        renderNode.setIcon(icon);

        // Update the click handler
        if (widget.getClickHandler() != null) {
            renderNode.setClickHandler(widget.getClickHandler());
        }
    }

    @NotNull
    private ItemStack createGlassPane(@NotNull String color) {
        // Use XSeries to obtain a colored glass pane
        try {
            // Try to obtain the colored glass pane
            return XVersionUtils.getColoredPlaneGlass(
                    Colors.valueOf(color.toUpperCase())
            );
        } catch (Exception e) {
            // Fall back to a plain glass pane
            return new ItemStack(Material.GLASS_PANE);
        }
    }
}
