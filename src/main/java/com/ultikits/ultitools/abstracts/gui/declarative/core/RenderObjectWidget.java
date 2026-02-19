package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;

/**
 * RenderObjectWidget 是描述实际渲染内容的 Widget 基类。
 * <p>
 * 与 {@link StatelessWidget} 和 {@link StatefulWidget} 不同，
 * RenderObjectWidget 直接对应一个 {@link RenderNode}，
 * 是渲染树的叶子节点。
 * <p>
 * <b>子类示例：</b>
 * <ul>
 *   <li>{@code ItemDisplay} - 显示一个物品</li>
 *   <li>{@code TextButton} - 显示一个文本按钮</li>
 *   <li>{@code Placeholder} - 占位符</li>
 * </ul>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public abstract class RenderObjectWidget extends Widget {

    /**
     * 创建一个新的 RenderObjectWidget。
     */
    protected RenderObjectWidget() {
        super();
    }

    /**
     * 创建一个新的 RenderObjectWidget，指定 key。
     *
     * @param key 用于稳定标识此 Widget 的键
     */
    protected RenderObjectWidget(SlotKey key) {
        super(key);
    }

    @Override
    @NotNull
    public abstract RenderObjectElement createElement();
}
