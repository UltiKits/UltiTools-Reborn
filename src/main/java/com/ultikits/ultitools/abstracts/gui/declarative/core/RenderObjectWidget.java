package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;

/**
 * RenderObjectWidget is the base Widget class for describing actual render content.
 * <p>
 * Unlike {@link StatelessWidget} and {@link StatefulWidget}, a RenderObjectWidget corresponds
 * directly to a {@link RenderNode} and is a leaf node of the render tree.
 * <p>
 * <b>Example subclasses:</b>
 * <ul>
 *   <li>{@code ItemDisplay} - displays an item</li>
 *   <li>{@code TextButton} - displays a text button</li>
 *   <li>{@code Placeholder} - a placeholder</li>
 * </ul>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public abstract class RenderObjectWidget extends Widget {

    /**
     * Creates a new RenderObjectWidget.
     */
    protected RenderObjectWidget() {
        super();
    }

    /**
     * Creates a new RenderObjectWidget with the given key.
     *
     * @param key the key used to stably identify this Widget
     */
    protected RenderObjectWidget(SlotKey key) {
        super(key);
    }

    @Override
    @NotNull
    public abstract RenderObjectElement createElement();
}
