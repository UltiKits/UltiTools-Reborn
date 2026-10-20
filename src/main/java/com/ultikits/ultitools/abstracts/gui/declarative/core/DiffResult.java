package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DiffResult represents the difference between two renders.
 * <p>
 * It holds every change that must be applied to the Inventory:
 * <ul>
 *   <li>added - newly added RenderNodes</li>
 *   <li>removed - deleted RenderNodes</li>
 *   <li>updated - RenderNodes whose content changed (same position)</li>
 *   <li>moved - RenderNodes whose position changed</li>
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
     * Creates an empty DiffResult.
     *
     * @return an empty DiffResult
     */
    @NotNull
    public static DiffResult empty() {
        return new Builder().build();
    }

    /**
     * Creates a new Builder.
     *
     * @return the Builder
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
     * Checks whether there are any changes.
     *
     * @return true if there are no changes
     */
    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && updated.isEmpty() && moved.isEmpty();
    }

    /**
     * Gets the total number of changes.
     *
     * @return the total change count
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
     * Represents a single node update.
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
         * Gets the slot index of the change.
         *
         * @return the slot index
         */
        public int getSlotIndex() {
            return oldNode.getSlotIndex();
        }
    }

    /**
     * Represents a single node move.
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
     * Builder for DiffResult.
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
