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
 * <h3>工作流程：</h3>
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
     * 初始化渲染器，创建根 Element。
     *
     * @param rootWidget 根 Widget
     * @param context    构建上下文
     */
    public void initialize(@NotNull Widget rootWidget, @NotNull BuildContext context) {
        scheduler.runOnMainThread(() -> {
            // 创建根 Element
            rootElement = rootWidget.createElement();

            rootElement.assignContext(context);
            rootElement.mount(null);

            // 执行初始构建
            performBuild();
        });
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
     */
    private void performBuild() {
        if (!scheduler.isOnMainThread()) {
            scheduler.runOnMainThread(this::performBuild);
            return;
        }

        if (rootElement == null) {
            return;
        }

        // 重建 Element 树
        rebuildElement(rootElement);

        // 收集 RenderNode
        List<RenderNode> newRenderNodes = collectRenderNodes(rootElement);

        // Diff
        List<RenderNode> oldNodes = lastRenderNodes != null ? lastRenderNodes : Collections.emptyList();
        DiffResult diffResult = differ.diff(oldNodes, newRenderNodes);

        // 应用变更
        applyDiff(diffResult);

        // 保存当前状态
        lastRenderNodes = newRenderNodes;

        // 更新点击处理器
        updateClickHandlers(newRenderNodes);
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

        // 如果是 RenderObjectElement，收集其 RenderNode
        if (element instanceof RenderObjectElement) {
            RenderNode node = ((RenderObjectElement) element).getRenderNode();
            if (node != null) {
                nodes.add(node);
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
            rootElement.unmount();
            rootElement = null;
        }

        lastRenderNodes = null;
        clickHandlers.clear();
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