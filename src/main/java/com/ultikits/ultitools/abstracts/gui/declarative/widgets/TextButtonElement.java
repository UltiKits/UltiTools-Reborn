package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderNode;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderObjectElement;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.ui.GridItem;
import java.util.Locale;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

/** The render element for a {@link TextButton}. @since 6.2.0 */
public class TextButtonElement extends RenderObjectElement {
    /** @param widget the button */
    public TextButtonElement(@NotNull TextButton widget) { super(widget); }

    @Override protected @NotNull RenderNode createRenderNode() {
        TextButton widget = (TextButton) getWidget();
        return new RenderNode(widget.getKey(), widget.getSlot(), item(widget));
    }

    @Override protected void updateRenderNode(@NotNull RenderNode renderNode) {
        TextButton widget = (TextButton) getWidget();
        renderNode.setSlotIndex(widget.getSlot());
        renderNode.setItem(item(widget));
    }

    private static GridItem item(TextButton widget) {
        ItemSpec icon = ItemSpec.builder(glassMaterial(widget.getColor()))
                .name(widget.getText()).lore(widget.getLore()).build();
        return widget.getClickHandler() == null ? GridItem.of(icon) : GridItem.of(icon, widget.getClickHandler());
    }

    private static Material glassMaterial(String color) {
        try {
            return Colors.valueOf(color.toUpperCase(Locale.ROOT)).getGlassPaneMaterial();
        } catch (IllegalArgumentException ignored) {
            return Material.GLASS_PANE;
        }
    }
}
