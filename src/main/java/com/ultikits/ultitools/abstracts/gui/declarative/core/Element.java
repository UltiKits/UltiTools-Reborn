package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Element 是 Widget 的实例化表示，负责管理 Widget 的生命周期和更新。
 * <p>
 * Element 是持久化的对象，当 Widget 重建时，Element 会被复用（如果类型匹配）。
 * 这种复用机制是框架高效更新的关键。
 * <p>
 * <b>Element 的职责：</b>
 * <ul>
 * <li>持有 Widget 引用（会随重建更新）</li>
 * <li>管理子 Element 树</li>
 * <li>协调 Widget 的创建、更新和销毁</li>
 * <li>为 RenderObjectElement 提供 RenderNode</li>
 * </ul>
 *
 * <p><strong>Element 类型：</strong></p>
 * <ul>
 * <li>{@link ComponentElement} - 组合型，管理子
 * Widget（StatelessWidget/StatefulWidget）</li>
 * <li>{@link RenderObjectElement} - 渲染型，对应实际的 RenderNode</li>
 * </ul>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 * @see Widget#createElement()
 */
public abstract class Element {

    @Nullable
    private Widget _widget;
    @Nullable
    private Element _parent;
    @Nullable
    private List<Element> _children;
    private boolean _dirty = false;
    private boolean _mounted = false;
    @Nullable
    private BuildContext _context;
    @Nullable
    private Runnable _rootBuildScheduler;

    protected Element(@NotNull Widget widget) {
        this._widget = widget;
    }

    /**
     * 为此 Element 分配 Context（仅用于根 Element 或测试）。
     *
     * @param context BuildContext
     */
    public void assignContext(@NotNull BuildContext context) {
        this._context = context;
    }

    /**
     * 将此 Element 挂载到树中。
     * <p>
     * 这个方法在 Element 首次插入树中时调用。
     * 子类应该在此处执行初始化工作，并挂载子 Element。
     *
     * @param parent 父 Element，如果为 null 表示这是根 Element
     */
    public void mount(@Nullable Element parent) {
        _parent = parent;
        _mounted = true;
        _dirty = false;

        if (parent != null) {
            _context = parent._context.child(this);
        } else if (_context == null) {
            throw new IllegalStateException("Root element must have context assigned before mount");
        }
    }

    /**
     * 使用新的 Widget 更新此 Element。
     * <p>
     * 这个方法在 Widget 重建时被调用。
     * 子类应该更新对 Widget 的引用，并执行必要的更新。
     *
     * @param newWidget 新的 Widget 实例
     */
    public void update(@NotNull Widget newWidget) {
        if (!canUpdate(newWidget)) {
            throw new IllegalArgumentException(
                    "Cannot update " + this + " with " + newWidget);
        }
        _widget = newWidget;
        // D-09 item 2: mark dirty here, in the BASE class, so StatefulElement and
        // StatelessElement (which both call super.update() before their own logic)
        // pick this up "for free" and move in step. Before this, only markNeedsBuild()
        // ever set _dirty, so a Widget swap via update() never scheduled a rebuild.
        _dirty = true;
    }

    /**
     * 检查此 Element 是否可以更新为给定的 Widget。
     *
     * @param newWidget 新的 Widget
     * @return 如果可以更新则返回 true
     */
    public boolean canUpdate(@NotNull Widget newWidget) {
        return _widget != null && newWidget.canUpdate(this);
    }

    /**
     * 从树中移除此 Element。
     * <p>
     * 这个方法在 Element 不再需要时被调用。
     * 子类应该在此处执行清理工作，并卸载子 Element。
     */
    public void unmount() {
        _mounted = false;
        _parent = null;

        if (_children != null) {
            for (Element child : _children) {
                child.unmount();
            }
            _children.clear();
            _children = null;
        }
    }

    /**
     * 标记此 Element 需要重建。
     * <p>
     * 这会安排框架在下一个帧中重建此 Element。
     */
    public void markNeedsBuild() {
        if (!_mounted) {
            return;
        }
        if (_dirty) {
            return;
        }
        _dirty = true;

        // 通知父 Element 或渲染器。
        //
        // 这是 State.setState() -> Element.markNeedsBuild() 这条链路唯一的出口：如果这个
        // Element 就是挂载的根（_parent == null），冒泡到这里就到头了——在
        // GuiRenderer 注册 _rootBuildScheduler 之前，setState() 修改了 State 的字段、
        // 把这个 Element 标记为 dirty，但从来没有安排任何一帧真正执行
        // performBuild()，Inventory 永远不会反映新状态。
        if (_parent != null) {
            _parent.markChildNeedsBuild(this);
        } else if (_rootBuildScheduler != null) {
            _rootBuildScheduler.run();
        }
    }

    /**
     * 标记子 Element 需要重建。
     *
     * @param child 子 Element
     */
    public void markChildNeedsBuild(@NotNull Element child) {
        // 默认实现：传递给父 Element；到达挂载的根（_parent == null）时，
        // 交给 GuiRenderer 注册的调度回调——见上面 markNeedsBuild() 的说明，
        // 同一个"冒泡到根即终止"的缺口在这里。
        if (_parent != null) {
            _parent.markChildNeedsBuild(child);
        } else if (_rootBuildScheduler != null) {
            _rootBuildScheduler.run();
        }
    }

    /**
     * 注册挂载的根 Element 用来把"需要重建"的冒泡通知转交给
     * {@link com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiRenderer} 的回调。
     * <p>
     * 框架内部方法——只有 GuiRenderer 在挂载/重新挂载根 Element 时调用它，模块作者不需要
     * （也不应该）自己调用。非根 Element 上这个字段永远是 null，因为冒泡在遇到第一个
     * {@code _parent != null} 的祖先时就会转发给那个祖先，不会走到这个分支。
     *
     * @param scheduler 根需要重建时调用的回调，通常是 {@code GuiRenderer::scheduleBuild}；
     *                  传 {@code null} 取消注册（渲染器销毁时）
     */
    public void setRootBuildScheduler(@Nullable Runnable scheduler) {
        this._rootBuildScheduler = scheduler;
    }

    /**
     * 执行重建。
     * <p>
     * 子类应该实现此方法来构建或重建 Widget 树。
     */
    public abstract void performRebuild();

    /**
     * 获取与此 Element 关联的 Widget。
     *
     * @return Widget 实例
     * @throws IllegalStateException 如果 Widget 为 null
     */
    @NotNull
    public Widget getWidget() {
        if (_widget == null) {
            throw new IllegalStateException("Widget is null");
        }
        return _widget;
    }

    /**
     * 获取父 Element。
     *
     * @return 父 Element，如果是根则返回 null
     */
    @Nullable
    public Element getParent() {
        return _parent;
    }

    /**
     * 获取子 Element 列表。
     *
     * @return 子 Element 列表，如果没有则返回空列表
     */
    @NotNull
    public List<Element> getChildren() {
        return _children != null ? _children : Collections.emptyList();
    }

    /**
     * 获取构建上下文。
     *
     * @return BuildContext
     */
    @NotNull
    public BuildContext getContext() {
        if (_context == null) {
            throw new IllegalStateException("Element context not initialized");
        }
        return _context;
    }

    /**
     * 检查是否已挂载。
     *
     * @return 如果已挂载则返回 true
     */
    public boolean isMounted() {
        return _mounted;
    }

    /**
     * 检查是否需要重建。
     *
     * @return 如果需要重建则返回 true
     */
    public boolean isDirty() {
        return _dirty;
    }

    /**
     * 清除 dirty 标记。
     */
    public void clearDirty() {
        _dirty = false;
    }

    /**
     * 添加子 Element。
     *
     * @param child 子 Element
     */
    protected void addChild(@NotNull Element child) {
        if (_children == null) {
            _children = new ArrayList<>();
        }
        _children.add(child);
    }

    /**
     * 更新单个子 Element。
     * <p>
     * 这是协调（reconciliation）的核心方法。
     * 它决定是复用现有 Element 还是创建新的。
     *
     * @param newWidget 新的 Widget
     * @param oldChild  旧的子 Element，可能为 null
     * @return 更新后的 Element
     */
    @Nullable
    protected Element updateChild(@Nullable Widget newWidget, @Nullable Element oldChild) {
        // 如果新 Widget 为 null，移除旧 Element
        if (newWidget == null) {
            if (oldChild != null) {
                oldChild.unmount();
            }
            return null;
        }

        // 如果没有旧 Element，创建新的
        if (oldChild == null) {
            Element newChild = newWidget.createElement();
            newChild.mount(this);
            return newChild;
        }

        // 如果 Widget 类型相同，复用 Element
        if (oldChild.canUpdate(newWidget)) {
            oldChild.update(newWidget);
            return oldChild;
        }

        // 类型不同，需要替换
        oldChild.unmount();
        Element newChild = newWidget.createElement();
        newChild.mount(this);
        return newChild;
    }

    /**
     * Reconciles a list of old child Elements against a list of new Widgets, pairing by
     * {@link SlotKey} where a Widget declares one and falling back to positional (index)
     * pairing where it does not -- so a caller that never supplies a key sees exactly the
     * same index-based behaviour this method replaces.
     * <p>
     * This is the shared implementation D-09 item 5 asks for:
     * {@link com.ultikits.ultitools.abstracts.gui.declarative.widgets.ContainerElement} and
     * {@link com.ultikits.ultitools.abstracts.gui.declarative.widgets.GridViewElement} were
     * literal twins, each hand-rolling the same
     * {@code Math.min(size)} index pairing and never reading {@link Widget#getKey()}. Both
     * now call this method instead of maintaining their own copy, so the two classes cannot
     * drift apart the way they did before this plan.
     * <p>
     * <b>算法（中文补充）：</b>先把旧子 Element 按"有 key"与"无 key"分两组；遍历新 Widget
     * 列表，有 key 的按 key 查找旧组中的匹配项，没有 key 的按顺序从"无 key 旧组"里取一个——
     * 找到匹配就调用 {@link #updateChild(Widget, Element)} 复用（或在类型不匹配时替换），
     * 找不到就新建。遍历结束后，两组里还剩下的旧 Element（没有被任何新 Widget 认领）全部
     * unmount。
     *
     * @param oldChildren the previously-mounted child Elements, in their current order
     * @param newWidgets  the new child Widgets, in their desired order
     * @return the reconciled child Elements, in {@code newWidgets}' order
     */
    @NotNull
    protected List<Element> updateChildren(@NotNull List<Element> oldChildren, @NotNull List<Widget> newWidgets) {
        List<Element> newChildren = new ArrayList<>(newWidgets.size());

        Map<SlotKey, Element> keyedOld = new HashMap<>();
        Deque<Element> unkeyedOld = new ArrayDeque<>();
        for (Element old : oldChildren) {
            SlotKey key = old.getWidget().getKey();
            if (key != null) {
                keyedOld.put(key, old);
            } else {
                unkeyedOld.addLast(old);
            }
        }

        for (Widget newWidget : newWidgets) {
            SlotKey key = newWidget.getKey();
            Element oldChild = key != null ? keyedOld.remove(key)
                    : (unkeyedOld.isEmpty() ? null : unkeyedOld.pollFirst());
            Element updated = updateChild(newWidget, oldChild);
            if (updated != null) {
                newChildren.add(updated);
            }
        }

        // Anything left in either group was not claimed by any new Widget -- unmount it.
        for (Element leftover : keyedOld.values()) {
            leftover.unmount();
        }
        for (Element leftover : unkeyedOld) {
            leftover.unmount();
        }

        return newChildren;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + _widget + ")";
    }

}