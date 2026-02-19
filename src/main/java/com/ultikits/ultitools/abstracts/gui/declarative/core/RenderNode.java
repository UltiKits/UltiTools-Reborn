package com.ultikits.ultitools.abstracts.gui.declarative.core;

import com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiRenderer;
import lombok.Getter;
import lombok.Setter;
import mc.obliviate.inventory.Icon;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * RenderNode 是虚拟渲染树的节点，代表一个要渲染的槽位。
 * <p>
 * RenderNode 是框架内部使用的对象，用于：
 * <ul>
 *   <li>描述每个槽位应该显示什么（Icon）</li>
 *   <li>记录槽位位置（slotIndex）</li>
 *   <li>支持稳定标识（slotKey）用于 diff</li>
 *   <li>组织成树结构以支持布局计算</li>
 * </ul>
 * <p>
 * <b>注意：</b> RenderNode 不直接操作 Bukkit Inventory，
 * 它只是一个虚拟表示，由 {@link GuiRenderer} 转换为实际的 Inventory 操作。
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class RenderNode {

    /**
     * 槽位的唯一标识，用于 diff 算法识别相同节点。
     * 如果为 null，则使用 slotIndex 进行识别。
     */
    @Nullable
    private final SlotKey key;

    /**
     * 槽位在 Inventory 中的索引（0-based）。
     * -1 表示尚未分配位置（如容器节点）。
     * -- GETTER --
     *  获取槽位索引。
     *
     *
     * -- SETTER --
     *  设置槽位索引。
     *
     */
    @Setter
    @Getter
    private int slotIndex;

    /**
     * 要在此槽位显示的 Icon。
     * 如果为 null，表示此槽位应该为空。
     */
    @Nullable
    private Icon icon;

    /**
     * 点击事件处理器。
     */
    @Nullable
    private Consumer<InventoryClickEvent> clickHandler;

    /**
     * 父节点。
     */
    @Nullable
    private RenderNode parent;

    /**
     * 子节点（用于容器类型）。
     */
    @NotNull
    private final List<RenderNode> children;

    /**
     * 节点元数据，用于存储额外信息。
     */
    @NotNull
    private final java.util.Map<String, Object> metadata;

    /**
     * 创建一个新的 RenderNode。
     *
     * @param key       槽位标识，可为 null
     * @param slotIndex 槽位索引
     */
    public RenderNode(@Nullable SlotKey key, int slotIndex) {
        this.key = key;
        this.slotIndex = slotIndex;
        this.children = new ArrayList<>();
        this.metadata = new java.util.HashMap<>();
    }

    /**
     * 创建一个叶子 RenderNode（没有子节点）。
     *
     * @param key       槽位标识
     * @param slotIndex 槽位索引
     * @param icon      图标
     */
    public RenderNode(@Nullable SlotKey key, int slotIndex, @Nullable Icon icon) {
        this(key, slotIndex);
        this.icon = icon;
    }

    // Getters and Setters

    /**
     * 获取槽位标识。
     *
     * @return SlotKey，可能为 null
     */
    @Nullable
    public SlotKey getKey() {
        return key;
    }

    /**
     * 获取图标。
     *
     * @return Icon，可能为 null
     */
    @Nullable
    public Icon getIcon() {
        return icon;
    }

    /**
     * 设置图标。
     *
     * @param icon 图标
     */
    public void setIcon(@Nullable Icon icon) {
        this.icon = icon;
    }

    /**
     * 获取点击处理器。
     *
     * @return 点击处理器，可能为 null
     */
    @Nullable
    public Consumer<InventoryClickEvent> getClickHandler() {
        return clickHandler;
    }

    /**
     * 设置点击处理器。
     *
     * @param clickHandler 点击处理器
     */
    public void setClickHandler(@Nullable Consumer<InventoryClickEvent> clickHandler) {
        this.clickHandler = clickHandler;
    }

    /**
     * 获取父节点。
     *
     * @return 父节点，可能为 null
     */
    @Nullable
    public RenderNode getParent() {
        return parent;
    }

    /**
     * 设置父节点。
     *
     * @param parent 父节点
     */
    void setParent(@Nullable RenderNode parent) {
        this.parent = parent;
    }

    /**
     * 获取子节点列表（不可修改）。
     *
     * @return 子节点列表
     */
    @NotNull
    public List<RenderNode> getChildren() {
        return Collections.unmodifiableList(children);
    }

    /**
     * 添加子节点。
     *
     * @param child 子节点
     */
    public void addChild(@NotNull RenderNode child) {
        children.add(child);
        child.setParent(this);
    }

    /**
     * 移除子节点。
     *
     * @param child 子节点
     */
    public void removeChild(@NotNull RenderNode child) {
        children.remove(child);
        child.setParent(null);
    }

    /**
     * 清空所有子节点。
     */
    public void clearChildren() {
        for (RenderNode child : children) {
            child.setParent(null);
        }
        children.clear();
    }

    /**
     * 获取元数据。
     *
     * @param key 键
     * @return 值，不存在则返回 null
     */
    @Nullable
    public Object getMetadata(@NotNull String key) {
        return metadata.get(key);
    }

    /**
     * 设置元数据。
     *
     * @param key   键
     * @param value 值
     */
    public void setMetadata(@NotNull String key, @Nullable Object value) {
        if (value == null) {
            metadata.remove(key);
        } else {
            metadata.put(key, value);
        }
    }

    /**
     * 检查是否是叶子节点（没有子节点）。
     *
     * @return 如果是叶子节点则返回 true
     */
    public boolean isLeaf() {
        return children.isEmpty();
    }

    /**
     * 检查是否是容器节点（有子节点）。
     *
     * @return 如果是容器节点则返回 true
     */
    public boolean isContainer() {
        return !children.isEmpty();
    }

    /**
     * 获取所有叶子节点的列表（递归）。
     *
     * @return 所有叶子节点
     */
    @NotNull
    public List<RenderNode> getAllLeaves() {
        List<RenderNode> leaves = new ArrayList<>();
        collectLeaves(this, leaves);
        return leaves;
    }

    private void collectLeaves(@NotNull RenderNode node, @NotNull List<RenderNode> leaves) {
        if (node.isLeaf()) {
            leaves.add(node);
        } else {
            for (RenderNode child : node.children) {
                collectLeaves(child, leaves);
            }
        }
    }

    /**
     * 计算子树占用的槽位数量。
     *
     * @return 槽位数量
     */
    public int calculateSlotCount() {
        if (isLeaf()) {
            return icon != null ? 1 : 0;
        }
        int count = 0;
        for (RenderNode child : children) {
            count += child.calculateSlotCount();
        }
        return count;
    }

    /**
     * 创建此节点的深拷贝（不包括子节点）。
     *
     * @return 新的 RenderNode
     */
    @NotNull
    public RenderNode copy() {
        RenderNode copy = new RenderNode(key, slotIndex);
        copy.icon = icon;
        copy.clickHandler = clickHandler;
        copy.metadata.putAll(metadata);
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RenderNode)) return false;
        RenderNode that = (RenderNode) o;
        // 优先使用 key 比较，如果没有 key 则使用 slotIndex
        if (key != null && that.key != null) {
            return key.equals(that.key);
        }
        return slotIndex == that.slotIndex;
    }

    @Override
    public int hashCode() {
        return key != null ? key.hashCode() : slotIndex;
    }

    @Override
    public String toString() {
        String keyStr = key != null ? key.getValue() : "null";
        String iconStr = icon != null ? "Icon" : "null";
        return "RenderNode(key=" + keyStr + ", slot=" + slotIndex + ", icon=" + iconStr + ")";
    }
}
