package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.abstracts.gui.declarative.core.*;
import mc.obliviate.inventory.Gui;
import mc.obliviate.inventory.Icon;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * GuiRenderer 负责协调 Widget 树的构建、diff 和 Inventory 更新。
 * <p>
 * 它是声明式框架的核心引擎，主要职责：
 * <ul>
 * <li>管理 Element 树的生命周期</li>
 * <li>调度重建（支持帧合并）</li>
 * <li>执行 diff 算法</li>
 * <li>应用变更到实际 Inventory</li>
 * </ul>
 *
 * <p><strong>工作流程：</strong></p>
 * 
 * <pre>
 * 1. 初始化：createRootElement → build RenderNode 树
 * 2. 渲染：diff → apply to Inventory
 * 3. 更新：setState → scheduleBuild → rebuild → diff → apply
 * </pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class GuiRenderer {

    private final Gui gui;
    private final Player player;
    private final GuiScheduler scheduler;
    private final RenderNodeDiffer differ;

    @Nullable
    private Element rootElement;
    @Nullable
    private List<RenderNode> lastRenderNodes;
    @Nullable
    private Supplier<Widget> widgetSupplier;
    @Nullable
    private BuildContext rootContext;

    // 槽位到点击处理器的映射
    private final Map<Integer, Consumer<InventoryClickEvent>> clickHandlers = new HashMap<>();

    /**
     * 创建 GuiRenderer。
     *
     * @param gui    关联的 Gui 实例
     * @param player 玩家
     */
    public GuiRenderer(@NotNull Gui gui, @NotNull Player player) {
        this(gui, player, new GuiScheduler());
    }

    /**
     * 创建 GuiRenderer，指定调度器。
     *
     * @param gui       关联的 Gui 实例
     * @param player    玩家
     * @param scheduler 调度器
     */
    public GuiRenderer(@NotNull Gui gui, @NotNull Player player, @NotNull GuiScheduler scheduler) {
        this.gui = gui;
        this.player = player;
        this.scheduler = scheduler;
        this.differ = new RenderNodeDiffer();
    }

    /**
     * 初始化渲染器。
     * <p>
     * D-09 item 1：不再接受一次性构建好的 Widget，而是接受一个 {@link Supplier}，
     * 在每一帧的开头重新调用它，从当前状态重新派生 Widget 树——这是
     * {@code UI = f(state)} 这句框架宣言第一次真正成立的地方。根 Element 的创建被
     * 推迟到第一次 {@link #performBuild()} 内部完成，这样 supplier 在首帧也只会被
     * 调用一次，而不是这里调一次、performBuild() 里再调一次。
     *
     * @param widgetSupplier 每帧重新求值的 Widget 树来源
     * @param context        构建上下文（根 Element 使用；后续帧的重建/重新挂载复用同一个）
     */
    public void initialize(@NotNull Supplier<Widget> widgetSupplier, @NotNull BuildContext context) {
        this.widgetSupplier = widgetSupplier;
        this.rootContext = context;
        scheduler.runOnMainThread(this::performBuild);
    }

    /**
     * 调度重建。
     * <p>
     * 使用帧合并机制，短时间内的多次调用只会触发一次重建。
     */
    public void scheduleBuild() {
        scheduler.scheduleFrame(this::performBuild);
    }

    /**
     * 立即执行重建（必须在主线程调用）。
     * <p>
     * D-09 item 1：每一帧的开头都会重新调用 {@link #widgetSupplier}，把结果喂给
     * 已挂载的根 Element（{@link Element#update}，其基类实现现在会标记 dirty——见
     * D-09 item 2），而不是只在 {@link #initialize} 时构建一次、之后再也不重新派生。
     */
    private void performBuild() {
        if (!scheduler.isOnMainThread()) {
            scheduler.runOnMainThread(this::performBuild);
            return;
        }

        if (widgetSupplier == null) {
            return;
        }

        // 重新从当前状态派生 Widget 树——每帧恰好调用一次
        Widget widget = widgetSupplier.get();

        if (rootElement == null) {
            mountRoot(widget);
        } else if (rootElement.canUpdate(widget)) {
            rootElement.update(widget);
        } else {
            // supplier 返回了与已挂载根类型不兼容的 Widget（T-05-52）：显式重新挂载，
            // 不能让 Element.update() 的 IllegalArgumentException 逃逸到被调度的帧里，
            // 那会把 Inventory 半途写坏。
            rootElement.unmount();
            mountRoot(widget);
        }

        // 重建 Element 树（仅重建 dirty 的子树）
        rebuildElement(rootElement);

        // 收集 RenderNode——快照，而不是活引用，见 collectRenderNodesRecursive
        List<RenderNode> newRenderNodes = collectRenderNodes(rootElement);

        // Diff
        List<RenderNode> oldNodes = lastRenderNodes != null ? lastRenderNodes : Collections.emptyList();
        DiffResult diffResult = differ.diff(oldNodes, newRenderNodes);

        // 应用变更
        applyDiff(diffResult);

        // 保存当前状态（快照，不会被下一帧对活 RenderNode 的原地修改追溯性改变）
        lastRenderNodes = newRenderNodes;

        // 更新点击处理器
        updateClickHandlers(newRenderNodes);
    }

    /**
     * 创建并挂载根 Element。
     * <p>
     * 供首次构建与"supplier 返回了不兼容类型"两种情况共用。
     *
     * @param widget 根 Widget
     */
    private void mountRoot(@NotNull Widget widget) {
        BuildContext context = Objects.requireNonNull(rootContext,
                "rootContext must be assigned by initialize() before performBuild() can mount a root");
        rootElement = widget.createElement();
        rootElement.assignContext(context);
        // The root Element's markNeedsBuild()/markChildNeedsBuild() bubbling terminates
        // in a no-op once it reaches an Element with no parent — that IS the mounted root.
        // Without this registration, State.setState() on a nested StatefulWidget mutates
        // its field and marks the tree dirty, but nothing ever calls scheduleBuild(), so
        // the mutation never reaches the Inventory. This is what closes the "setState ->
        // automatic repaint" half of D-09/WIRE-02 for the documented CounterButton shape.
        rootElement.setRootBuildScheduler(this::scheduleBuild);
        rootElement.mount(null);
    }

    /**
     * 递归重建 Element 树。
     *
     * @param element 要重建的 Element
     */
    private void rebuildElement(@NotNull Element element) {
        if (element.isDirty()) {
            element.performRebuild();
        }

        for (Element child : element.getChildren()) {
            rebuildElement(child);
        }
    }

    /**
     * 收集所有 RenderNode（后序遍历）。
     *
     * @param element 根 Element
     * @return RenderNode 列表
     */
    @NotNull
    private List<RenderNode> collectRenderNodes(@NotNull Element element) {
        List<RenderNode> nodes = new ArrayList<>();
        collectRenderNodesRecursive(element, nodes);
        return nodes;
    }

    private void collectRenderNodesRecursive(@NotNull Element element, @NotNull List<RenderNode> nodes) {
        // 先收集子节点
        for (Element child : element.getChildren()) {
            collectRenderNodesRecursive(child, nodes);
        }

        // 如果是 RenderObjectElement，收集其 RenderNode 的快照
        //
        // D-09 item 3：getRenderNode() 对同一个 Element 永远返回同一个实例
        // （RenderObjectElement 只在第一次访问时创建它，此后原地修改）。如果这里直接
        // 把这个活引用塞进 lastRenderNodes，下一帧 RenderNodeDiffer.diff() 拿到的
        // “旧节点”和“新节点”会是同一个对象——比较永远等于自身，diff 永远看不到变化。
        // .copy()（RenderNode.java 里早就写好、此前零调用方的方法）在这里把这次帧的
        // 状态拍成快照，后续帧对活节点的原地修改不会追溯性地改写这份快照。
        if (element instanceof RenderObjectElement) {
            RenderNode node = ((RenderObjectElement) element).getRenderNode();
            if (node != null) {
                nodes.add(node.copy());
            }
        }
    }

    /**
     * 应用 diff 结果到 Inventory。
     *
     * @param diffResult diff 结果
     */
    private void applyDiff(@NotNull DiffResult diffResult) {
        if (diffResult.isEmpty()) {
            return;
        }

        // 1. 处理删除
        for (RenderNode removed : diffResult.getRemoved()) {
            clearSlot(removed.getSlotIndex());
        }

        // 2. 处理移动（先清除原位置）
        for (DiffResult.RenderNodeMove move : diffResult.getMoved()) {
            clearSlot(move.getFromSlot());
        }

        // 3. 处理新增
        for (RenderNode added : diffResult.getAdded()) {
            setSlot(added.getSlotIndex(), added.getIcon());
        }

        // 4. 处理更新
        for (DiffResult.RenderNodeUpdate update : diffResult.getUpdated()) {
            setSlot(update.getSlotIndex(), update.getNewNode().getIcon());
        }

        // 5. 处理移动（设置新位置）
        for (DiffResult.RenderNodeMove move : diffResult.getMoved()) {
            setSlot(move.getToSlot(), move.getNode().getIcon());
        }
    }

    /**
     * 更新点击处理器映射。
     *
     * @param renderNodes 当前的 RenderNode 列表
     */
    private void updateClickHandlers(@NotNull List<RenderNode> renderNodes) {
        clickHandlers.clear();

        for (RenderNode node : renderNodes) {
            if (node.getClickHandler() != null) {
                clickHandlers.put(node.getSlotIndex(), node.getClickHandler());
            }
        }
    }

    /**
     * 处理点击事件。
     *
     * @param event 点击事件
     */
    public void handleClick(@NotNull InventoryClickEvent event) {
        int slot = event.getSlot();
        Consumer<InventoryClickEvent> handler = clickHandlers.get(slot);
        if (handler != null) {
            handler.accept(event);
        }
    }

    /**
     * 设置槽位内容。
     *
     * @param slot 槽位索引
     * @param icon 图标
     */
    private void setSlot(int slot, @Nullable Icon icon) {
        if (slot < 0 || slot >= gui.getSize()) {
            return;
        }
        if (icon != null) {
            gui.addItem(slot, icon);
        }
    }

    /**
     * 清空槽位。
     *
     * @param slot 槽位索引
     */
    private void clearSlot(int slot) {
        if (slot < 0 || slot >= gui.getSize()) {
            return;
        }
        // 使用空气 ItemStack 清空
        gui.addItem(slot, new Icon(new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR)));
    }

    /**
     * 销毁渲染器，清理资源。
     */
    public void dispose() {
        scheduler.cancelAll();

        if (rootElement != null) {
            rootElement.setRootBuildScheduler(null);
            rootElement.unmount();
            rootElement = null;
        }

        lastRenderNodes = null;
        clickHandlers.clear();
        widgetSupplier = null;
        rootContext = null;
    }

    /**
     * 获取关联的 Gui 实例。
     *
     * @return Gui 实例
     */
    @NotNull
    public Gui getGui() {
        return gui;
    }

    /**
     * 获取调度器。
     *
     * @return GuiScheduler
     */
    @NotNull
    public GuiScheduler getScheduler() {
        return scheduler;
    }
}