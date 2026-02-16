package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.abstracts.gui.declarative.core.DiffResult;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderNode;
import com.ultikits.ultitools.abstracts.gui.declarative.core.SlotKey;
import mc.obliviate.inventory.Icon;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * RenderNodeDiffer 实现了 RenderNode 树的 diff 算法。
 * <p>
 * 算法基于 key 的比较：
 * <ul>
 * <li>如果旧节点和新节点有相同的 key，则认为是同一个节点（可能更新）</li>
 * <li>如果没有 key，则使用 slotIndex 进行比较</li>
 * <li>新增、删除、更新的节点都会被记录在 DiffResult 中</li>
 * </ul>
 *
 * <h3>算法步骤：</h3>
 * <ol>
 * <li>构建旧节点的 key → node 映射</li>
 * <li>遍历新节点列表，匹配旧节点</li>
 * <li>记录匹配的节点（更新或移动）</li>
 * <li>记录未匹配的节点（新增）</li>
 * <li>记录未匹配的旧节点（删除）</li>
 * </ol>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class RenderNodeDiffer {

    /**
     * 比较两组 RenderNode，返回差异结果。
     *
     * @param oldNodes 旧的 RenderNode 列表
     * @param newNodes 新的 RenderNode 列表
     * @return DiffResult 包含所有变更
     */
    @NotNull
    public DiffResult diff(@NotNull List<RenderNode> oldNodes, @NotNull List<RenderNode> newNodes) {
        DiffResult.Builder builder = DiffResult.builder();
        Map<NodeKey, RenderNode> oldNodeMap = new HashMap<>();

        // 构建旧节点的映射
        for (RenderNode node : oldNodes) {
            oldNodeMap.put(NodeKey.from(node), node);
        }

        // 跟踪已匹配的旧节点
        Set<RenderNode> matchedOldNodes = new HashSet<>();

        // 遍历新节点
        for (RenderNode newNode : newNodes) {
            RenderNode oldNode = oldNodeMap.get(NodeKey.from(newNode));

            if (oldNode == null) {
                // 新增节点
                if (newNode.getIcon() != null) {
                    builder.addAdded(newNode);
                }
            } else {
                // 匹配到旧节点
                matchedOldNodes.add(oldNode);

                if (hasChanged(oldNode, newNode)) {
                    builder.addUpdated(oldNode, newNode);
                }

                if (oldNode.getSlotIndex() != newNode.getSlotIndex()) {
                    builder.addMoved(newNode, oldNode.getSlotIndex(), newNode.getSlotIndex());
                }
            }
        }

        // 找出被删除的节点
        for (RenderNode oldNode : oldNodes) {
            if (!matchedOldNodes.contains(oldNode) && oldNode.getIcon() != null) {
                builder.addRemoved(oldNode);
            }
        }

        return builder.build();
    }

    /**
     * 比较单个节点的变化。
     *
     * @param oldNode 旧节点
     * @param newNode 新节点
     * @return 如果有变化返回 true
     */
    private boolean hasChanged(@NotNull RenderNode oldNode, @NotNull RenderNode newNode) {
        // 比较 Icon
        Icon oldIcon = oldNode.getIcon();
        Icon newIcon = newNode.getIcon();

        if (oldIcon == null && newIcon == null) {
            return false;
        }
        if (oldIcon == null || newIcon == null) {
            return true;
        }

        // 比较 Icon 的核心属性
        if (!iconsEqual(oldIcon, newIcon)) {
            return true;
        }

        return false;
    }

    /**
     * 比较两个 Icon 是否相等。
     * <p>
     * 注意：这里的比较是基于显示效果的，不是基于对象引用。
     *
     * @param a 第一个 Icon
     * @param b 第二个 Icon
     * @return 如果相等返回 true
     */
    private boolean iconsEqual(@NotNull Icon a, @NotNull Icon b) {
        // 比较 ItemStack
        if (!a.getItem().equals(b.getItem())) {
            return false;
        }

        // 比较显示名称
        ItemMeta aMeta = a.getItem().getItemMeta();
        Component aName = (aMeta != null && aMeta.hasDisplayName()) ? aMeta.displayName() : null;

        ItemMeta bMeta = b.getItem().getItemMeta();
        Component bName = (bMeta != null && bMeta.hasDisplayName()) ? bMeta.displayName() : null;

        return Objects.equals(aName, bName);
    }

    /**
     * 用于标识 RenderNode 的键。
     */
    private static class NodeKey {
        @Nullable
        private final SlotKey slotKey;
        private final int slotIndex;

        private NodeKey(@Nullable SlotKey slotKey, int slotIndex) {
            this.slotKey = slotKey;
            this.slotIndex = slotIndex;
        }

        @NotNull
        static NodeKey from(@NotNull RenderNode node) {
            return new NodeKey(node.getKey(), node.getSlotIndex());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof NodeKey))
                return false;
            NodeKey other = (NodeKey) o;
            // 优先使用 slotKey，如果没有则使用 slotIndex
            if (slotKey != null && other.slotKey != null) {
                return slotKey.equals(other.slotKey);
            }
            return slotIndex == other.slotIndex;
        }

        @Override
        public int hashCode() {
            return slotKey != null ? slotKey.hashCode() : slotIndex;
        }

        @Override
        public String toString() {
            return slotKey != null ? "key:" + slotKey.getValue() : "slot:" + slotIndex;
        }
    }
}
