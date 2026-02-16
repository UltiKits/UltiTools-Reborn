package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;

/**
 * StatelessWidget 是一个不需要可变状态的 Widget。
 * <p>
 * 它完全依赖于构造时传入的配置参数来构建 UI。
 * 当配置变化时，会创建新的 StatelessWidget 实例，
 * 框架会自动 diff 并更新必要的部分。
 * <p>
 * <b>适用场景：</b>
 * <ul>
 *   <li>纯展示性 UI（如标题、图标、静态文本）</li>
 *   <li>完全由父 Widget 控制状态的 UI</li>
 *   <li>没有副作用的纯函数式组件</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * public class ItemRow extends StatelessWidget {
 *     private final ItemStack item;
 *     private final String name;
 *     private final Runnable onClick;
 *     
 *     public ItemRow(ItemStack item, String name, Runnable onClick) {
 *         this.item = item;
 *         this.name = name;
 *         this.onClick = onClick;
 *     }
 *     
 *     @Override
 *     public Widget build(BuildContext context) {
 *         return Row.builder()
 *             .children(
 *                 ItemDisplay.builder(item).build(),
 *                 TextButton.builder()
 *                     .text(name)
 *                     .onClick(onClick)
 *                     .build()
 *             )
 *             .build();
 *     }
 * }
 * }</pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 * @see StatefulWidget
 * @see Widget
 */
public abstract class StatelessWidget extends Widget {

    /**
     * 创建一个新的 StatelessWidget。
     */
    protected StatelessWidget() {
        super();
    }

    /**
     * 创建一个新的 StatelessWidget，指定 key。
     *
     * @param key 用于稳定标识此 Widget 的键
     */
    protected StatelessWidget(SlotKey key) {
        super(key);
    }

    /**
     * 构建此 Widget 的子树。
     * <p>
     * 这个方法在以下情况会被调用：
     * <ul>
     *   <li>Widget 首次被创建时</li>
     *   <li>父 Widget 重建时</li>
     *   <li>依赖的数据发生变化时</li>
     * </ul>
     * <p>
     * <b>重要：</b> build 方法应该是纯函数，不应该有副作用。
     * 不要在 build 中修改状态、执行 I/O 操作或注册监听器。
     *
     * @param context 构建上下文，包含玩家、GUI 配置等信息
     * @return 子 Widget 树
     */
    @NotNull
    public abstract Widget build(@NotNull BuildContext context);

    @Override
    @NotNull
    public Element createElement() {
        return new StatelessElement(this);
    }
}
