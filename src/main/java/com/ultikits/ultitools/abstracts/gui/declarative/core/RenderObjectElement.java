package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * RenderObjectElement is the Element that corresponds to a RenderNode.
 * <p>
 * This is a leaf node of the render tree, and ultimately produces a RenderNode.
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public abstract class RenderObjectElement extends Element {

    @Nullable
    private RenderNode _renderNode;

    public RenderObjectElement(@NotNull Widget widget) {
        super(widget);
    }

    /**
     * Creates the RenderNode corresponding to this Element.
     *
     * @return the new RenderNode
     */
    @NotNull
    protected abstract RenderNode createRenderNode();

    /**
     * Updates the RenderNode.
     *
     * @param renderNode the RenderNode to update
     */
    protected abstract void updateRenderNode(@NotNull RenderNode renderNode);

    /**
     * Gets the RenderNode, creating it first if it has not been created yet.
     *
     * @return the RenderNode
     */
    @NotNull
    public RenderNode getRenderNode() {
        if (_renderNode == null) {
            _renderNode = createRenderNode();
        }
        return _renderNode;
    }

    @Override
    public void performRebuild() {
        updateRenderNode(getRenderNode());
        clearDirty();
    }

    @Override
    public void unmount() {
        _renderNode = null;
        super.unmount();
    }
}
