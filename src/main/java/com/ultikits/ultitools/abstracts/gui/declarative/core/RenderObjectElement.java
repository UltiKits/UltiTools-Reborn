package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * RenderObjectElement 是对应 RenderNode 的 Element。
 * <p>
 * 这是渲染树的叶子节点，最终会生成 RenderNode。
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
     * 创建与此 Element 对应的 RenderNode。
     *
     * @return 新的 RenderNode
     */
    @NotNull
    protected abstract RenderNode createRenderNode();

    /**
     * 更新 RenderNode。
     *
     * @param renderNode 要更新的 RenderNode
     */
    protected abstract void updateRenderNode(@NotNull RenderNode renderNode);

    /**
     * 获取 RenderNode。
     * 如果尚未创建，则创建它。
     *
     * @return RenderNode
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
