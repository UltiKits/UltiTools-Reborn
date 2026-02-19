package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * State 是 StatefulWidget 的可变状态。
 * <p>
 * State 对象由框架管理，具有以下特性：
 * <ul>
 *   <li>在 StatefulWidget 插入树中时创建</li>
 *   <li>在 StatefulWidget 从树中移除时销毁</li>
 *   <li>在 StatefulWidget 重建时复用</li>
 * </ul>
 *
 * <h3>生命周期：</h3>
 * <ol>
 *   <li>{@link #initState()} - 初始化状态，只调用一次</li>
 *   <li>{@link #build(BuildContext)} - 构建 UI，可能调用多次</li>
 *   <li>{@link #didUpdateWidget(StatefulWidget)} - 配置变化时调用</li>
 *   <li>{@link #dispose()} - 清理资源，只调用一次</li>
 * </ol>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * class CounterState extends State<CounterWidget> {
 *     private int count = 0;
 *     
 *     @Override
 *     void initState() {
 *         super.initState();
 *         // 初始化工作
 *     }
 *     
 *     @Override
 *     Widget build(BuildContext context) {
 *         return TextButton.builder()
 *             .text("Clicked: " + count)
 *             .onClick(() -> setState(() -> count++))
 *             .build();
 *     }
 * }
 * }</pre>
 *
 * @param <T> 对应的 StatefulWidget 类型
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public abstract class State<T extends StatefulWidget> {

    @Nullable
    private T _widget;
    @Nullable
    private StatefulElement _element;
    private boolean _dirty = false;
    private boolean _mounted = false;

    /**
     * 创建一个新的 State。
     */
    public State() {
    }

    /**
     * 获取与此 State 关联的 Widget。
     *
     * @return Widget 实例
     * @throws IllegalStateException 如果 State 尚未挂载
     */
    @NotNull
    public T getWidget() {
        if (_widget == null) {
            throw new IllegalStateException("State not mounted yet");
        }
        return _widget;
    }

    /**
     * 检查 State 是否已挂载到树中。
     *
     * @return 如果已挂载则返回 true
     */
    public boolean isMounted() {
        return _mounted;
    }

    /**
     * 标记 State 为 dirty，并安排重建。
     * <p>
     * 这个方法通知框架 State 对象已发生变化，需要重建 UI。
     * 框架会在下一个帧中调用 build 方法。
     * <p>
     * <b>重要：</b> 回调函数中应该只修改状态，不应该有副作用。
     *
     * <h3>使用示例：</h3>
     * <pre>{@code
     * void handleClick() {
     *     setState(() -> {
     *         _counter++;  // 只修改状态
     *     });
     * }
     * }</pre>
     *
     * @param fn 修改状态的回调函数
     */
    public void setState(@NotNull VoidCallback fn) {
        if (!_mounted) {
            throw new IllegalStateException("setState() called after dispose()");
        }
        
        // 执行状态修改
        fn.call();
        
        // 标记为 dirty 并安排重建
        _dirty = true;
        if (_element != null) {
            _element.markNeedsBuild();
        }
    }

    /**
     * 初始化状态。
     * <p>
     * 这个方法在 State 对象创建后、插入树中前调用，只调用一次。
     * 可以在此订阅流、启动动画或执行其他一次性初始化工作。
     * <p>
     * 必须调用 super.initState()。
     */
    protected void initState() {
        // 子类重写
    }

    /**
     * 清理资源。
     * <p>
     * 这个方法在 State 对象从树中永久移除时调用，只调用一次。
     * 必须在此取消订阅、停止动画或执行其他清理工作。
     * <p>
     * 必须调用 super.dispose()。
     */
    protected void dispose() {
        _mounted = false;
    }

    /**
     * Widget 配置发生变化时调用。
     * <p>
     * 当父 Widget 重建并创建新的 StatefulWidget 实例时调用。
     * 可以在此比较新旧 Widget 的属性，并相应地调整状态。
     *
     * @param oldWidget 旧的 Widget 实例
     */
    @SuppressWarnings("unchecked")
    protected void didUpdateWidget(@NotNull T oldWidget) {
        // 子类重写
    }

    /**
     * 构建此 State 的 UI。
     * <p>
     * 这个方法在以下情况会被调用：
     * <ul>
     *   <li>initState 之后</li>
     *   <li>didUpdateWidget 之后</li>
     *   <li>setState 之后</li>
     *   <li>依赖的 InheritedWidget 变化时</li>
     * </ul>
     *
     * @param context 构建上下文
     * @return Widget 树
     */
    @NotNull
    public abstract Widget build(@NotNull BuildContext context);

    // Package-private methods for framework

    void setElement(@Nullable StatefulElement element) {
        _element = element;
    }

    @SuppressWarnings("unchecked")
    void setWidget(@NotNull StatefulWidget widget) {
        _widget = (T) widget;
    }

    void setMounted(boolean mounted) {
        _mounted = mounted;
    }

    boolean isDirty() {
        return _dirty;
    }

    void clearDirty() {
        _dirty = false;
    }

    /**
     * 内部方法：调用 didUpdateWidget，使用原始类型避免泛型问题。
     */
    @SuppressWarnings("unchecked")
    void didUpdateWidgetInternal(@NotNull StatefulWidget oldWidget) {
        didUpdateWidget((T) oldWidget);
    }
}
