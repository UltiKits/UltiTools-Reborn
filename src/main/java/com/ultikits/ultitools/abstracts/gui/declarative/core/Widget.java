package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Widget 是声明式 UI 框架的核心抽象类。
 * <p>
 * Widget 是不可变的、轻量级的配置对象，用于描述 UI 的一部分。
 * 每次状态变化都会创建新的 Widget 实例，但框架会通过 diff 算法高效地复用 Element。
 * <p>
 * 核心理念：<b>UI = f(state)</b>
 * <p>
 * Widget 本身不包含任何可变状态，也不直接操作 Inventory。
 * 它只负责描述"应该显示什么"，而不管"如何显示"和"显示在哪里"。
 *
 * <p><strong>子类类型：</strong></p>
 * <ul>
 *   <li>{@link StatelessWidget} - 无状态 Widget，纯函数式，只依赖输入参数</li>
 *   <li>{@link StatefulWidget} - 有状态 Widget，可以响应 setState 重建</li>
 *   <li>{@link RenderObjectWidget} - 渲染 Widget，对应实际的 RenderNode</li>
 * </ul>
 *
 * <p><strong>使用示例：</strong></p>
 * <pre>{@code
 * // 创建一个计数器 Widget
 * public class CounterWidget extends StatefulWidget {
 *     @Override
 *     public State createState() {
 *         return new CounterState();
 *     }
 * }
 *
 * class CounterState extends State<CounterWidget> {
 *     private int count = 0;
 *
 *     @Override
 *     public Widget build(BuildContext context) {
 *         return TextButton.builder()
 *             .text("Count: " + count)
 *             .onClick(() -> setState(() -> count++))
 *             .build();
 *     }
 * }
 * }</pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 * @see StatelessWidget
 * @see StatefulWidget
 * @see Element
 */
public abstract class Widget {

    /**
     * 可选的键，用于在列表中稳定标识此 Widget。
     * <p>
     * 当 Widget 在列表中时，应该提供一个稳定的 key 来帮助框架识别
     * 哪些 Widget 是相同的，从而优化 diff 性能。
     */
    @Nullable
    private final SlotKey key;

    /**
     * 创建一个新的 Widget 实例。
     */
    protected Widget() {
        this(null);
    }

    /**
     * 创建一个新的 Widget 实例，并指定 key。
     *
     * @param key 用于稳定标识此 Widget 的键
     */
    protected Widget(@Nullable SlotKey key) {
        this.key = key;
    }

    /**
     * 获取此 Widget 的 key。
     *
     * @return key，如果没有设置则返回 null
     */
    @Nullable
    public SlotKey getKey() {
        return key;
    }

    /**
     * 为此 Widget 创建对应的 Element。
     * <p>
     * 这是框架内部使用的方法，子类应该根据 Widget 类型返回适当的 Element 实现。
     * <ul>
     *   <li>StatelessWidget 返回 {@link StatelessElement}</li>
     *   <li>StatefulWidget 返回 {@link StatefulElement}</li>
     *   <li>RenderObjectWidget 返回 {@link RenderObjectElement}</li>
     * </ul>
     *
     * @return 对应的 Element 实例
     */
    @NotNull
    public abstract Element createElement();

    /**
     * 检查此 Widget 是否可以更新给定的 Element。
     * <p>
     * 默认实现检查运行时类型是否相同。
     * 子类可以重写此方法以提供自定义的更新逻辑。
     *
     * @param element 要检查的 Element
     * @return 如果可以更新则返回 true
     */
    public boolean canUpdate(@NotNull Element element) {
        return element.getWidget().getClass() == this.getClass();
    }

    /**
     * 检查两个 Widget 是否代表相同的配置。
     * <p>
     * 如果两个 Widget 的 key 相同且运行时类型相同，则认为它们是相同的 Widget。
     * 这用于 diff 算法决定是否复用 Element。
     *
     * @param other 另一个 Widget
     * @return 如果相同则返回 true
     */
    public boolean isSameWidget(@Nullable Widget other) {
        if (this == other) return true;
        if (other == null) return false;
        if (this.getClass() != other.getClass()) return false;
        return Objects.equals(this.key, other.key);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Widget)) return false;
        Widget widget = (Widget) o;
        return Objects.equals(key, widget.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key);
    }

    @Override
    public String toString() {
        String keyString = key != null ? " key=" + key : "";
        return getClass().getSimpleName() + "(" + keyString + ")";
    }
}

