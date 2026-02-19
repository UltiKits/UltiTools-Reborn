package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DiffResult 表示两次渲染之间的差异。
 * <p>
 * 它包含需要应用到 Inventory 的所有变更：
 * <ul>
 *   <li>added - 新增的 RenderNode</li>
 *   <li>removed - 删除的 RenderNode</li>
 *   <li>updated - 内容变化的 RenderNode（相同位置）</li>
 *   <li>moved - 位置变化的 RenderNode</li>
 * </ul>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class DiffResult {

    @NotNull
    private final List<RenderNode> added;
    @NotNull
    private final List<RenderNode> removed;
    @NotNull
    private final List<RenderNodeUpdate> updated;
    @NotNull
    private final List<RenderNodeMove> moved;

    private DiffResult(@NotNull Builder builder) {
        this.added = Collections.unmodifiableList(new ArrayList<>(builder.added));
        this.removed = Collections.unmodifiableList(new ArrayList<>(builder.removed));
        this.updated = Collections.unmodifiableList(new ArrayList<>(builder.updated));
        this.moved = Collections.unmodifiableList(new ArrayList<>(builder.moved));
    }

    /**
     * 创建一个空的 DiffResult。
     *
     * @return 空的 DiffResult
     */
    @NotNull
    public static DiffResult empty() {
        return new Builder().build();
    }

    /**
     * 创建一个新的 Builder。
     *
     * @return Builder
     */
    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    // Getters

    @NotNull
    public List<RenderNode> getAdded() {
        return added;
    }

    @NotNull
    public List<RenderNode> getRemoved() {
        return removed;
    }

    @NotNull
    public List<RenderNodeUpdate> getUpdated() {
        return updated;
    }

    @NotNull
    public List<RenderNodeMove> getMoved() {
        return moved;
    }

    /**
     * 检查是否有任何变更。
     *
     * @return 如果没有变更则返回 true
     */
    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && updated.isEmpty() && moved.isEmpty();
    }

    /**
     * 获取变更总数。
     *
     * @return 变更总数
     */
    public int getChangeCount() {
        return added.size() + removed.size() + updated.size() + moved.size();
    }

    @Override
    public String toString() {
        return "DiffResult(added=" + added.size() +
                ", removed=" + removed.size() +
                ", updated=" + updated.size() +
                ", moved=" + moved.size() + ")";
    }

    /**
     * 表示一个节点更新。
     */
    public static class RenderNodeUpdate {
        @NotNull
        private final RenderNode oldNode;
        @NotNull
        private final RenderNode newNode;

        public RenderNodeUpdate(@NotNull RenderNode oldNode, @NotNull RenderNode newNode) {
            this.oldNode = oldNode;
            this.newNode = newNode;
        }

        @NotNull
        public RenderNode getOldNode() {
            return oldNode;
        }

        @NotNull
        public RenderNode getNewNode() {
            return newNode;
        }

        /**
         * 获取变更的槽位索引。
         *
         * @return 槽位索引
         */
        public int getSlotIndex() {
            return oldNode.getSlotIndex();
        }
    }

    /**
     * 表示一个节点移动。
     */
    public static class RenderNodeMove {
        @NotNull
        private final RenderNode node;
        private final int fromSlot;
        private final int toSlot;

        public RenderNodeMove(@NotNull RenderNode node, int fromSlot, int toSlot) {
            this.node = node;
            this.fromSlot = fromSlot;
            this.toSlot = toSlot;
        }

        @NotNull
        public RenderNode getNode() {
            return node;
        }

        public int getFromSlot() {
            return fromSlot;
        }

        public int getToSlot() {
            return toSlot;
        }
    }

    /**
     * DiffResult 的构建器。
     */
    public static class Builder {
        private final List<RenderNode> added = new ArrayList<>();
        private final List<RenderNode> removed = new ArrayList<>();
        private final List<RenderNodeUpdate> updated = new ArrayList<>();
        private final List<RenderNodeMove> moved = new ArrayList<>();

        public Builder addAdded(@NotNull RenderNode node) {
            added.add(node);
            return this;
        }

        public Builder addRemoved(@NotNull RenderNode node) {
            removed.add(node);
            return this;
        }

        public Builder addUpdated(@NotNull RenderNode oldNode, @NotNull RenderNode newNode) {
            updated.add(new RenderNodeUpdate(oldNode, newNode));
            return this;
        }

        public Builder addMoved(@NotNull RenderNode node, int fromSlot, int toSlot) {
            moved.add(new RenderNodeMove(node, fromSlot, toSlot));
            return this;
        }

        public DiffResult build() {
            return new DiffResult(this);
        }
    }
}
