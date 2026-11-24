package com.ultikits.ultitools.abstracts.gui.declarative.core;

import com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiRenderer;
import lombok.Getter;
import lombok.Setter;
import com.ultikits.ultitools.ui.GridItem;
import com.ultikits.ultitools.ui.SlotBinding;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RenderNode is a node in the virtual render tree, representing one slot to be rendered.
 * <p>
 * RenderNode is a framework-internal object used to:
 * <ul>
 *   <li>describe a slot's immutable item and action, or a container binding</li>
 *   <li>record the slot's grid position (slotIndex)</li>
 *   <li>retain stable node identification (slotKey)</li>
 *   <li>organize into a tree structure to support layout calculation</li>
 * </ul>
 * <p>
 * <b>Note:</b> RenderNode never touches a Bukkit Inventory directly;
 * {@link GuiRenderer} collects its contents into an immutable grid snapshot for the UI service.
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class RenderNode {

    /**
     * The slot's unique identifier, used to recognize the same logical node.
     * If null, slotIndex is used for identification instead.
     */
    @Nullable
    private final SlotKey key;

    /**
     * The slot's index within the Inventory (0-based).
     * -1 means no position has been assigned yet (e.g. a container node).
     * -- GETTER --
     *  Gets the slot index.
     *
     *
     * -- SETTER --
     *  Sets the slot index.
     *
     */
    @Setter
    @Getter
    private int slotIndex;

    /** The immutable icon and action, mutually exclusive with a container binding. */
    @Nullable
    private GridItem item;

    /** The container binding, mutually exclusive with an icon. */
    @Nullable
    private SlotBinding binding;

    /**
     * The parent node.
     */
    @Nullable
    private RenderNode parent;

    /**
     * The child nodes (for container-type nodes).
     */
    @NotNull
    private final List<RenderNode> children;

    /**
     * Node metadata, used to store extra information.
     */
    @NotNull
    private final java.util.Map<String, Object> metadata;

    /**
     * Creates a new RenderNode.
     *
     * @param key       the slot identifier, may be null
     * @param slotIndex the slot index
     */
    public RenderNode(@Nullable SlotKey key, int slotIndex) {
        this.key = key;
        this.slotIndex = slotIndex;
        this.children = new ArrayList<>();
        this.metadata = new java.util.HashMap<>();
    }

    /**
     * Creates a leaf RenderNode (no child nodes).
     *
     * @param key       the slot identifier
     * @param slotIndex the slot index
     * @param item      the icon and optional action
     */
    public RenderNode(@Nullable SlotKey key, int slotIndex, @Nullable GridItem item) {
        this(key, slotIndex);
        this.item = item;
    }

    // Getters and Setters

    /**
     * Gets the slot identifier.
     *
     * @return the SlotKey, may be null
     */
    @Nullable
    public SlotKey getKey() {
        return key;
    }

    /** @return the icon and action, or null */
    @Nullable
    public GridItem getItem() {
        return item;
    }

    /**
     * Sets an icon (or empty content) and clears any binding.
     * @param item the icon, or null
     */
    public void setItem(@Nullable GridItem item) {
        this.item = item;
        this.binding = null;
    }

    /** @return the container binding, or null */
    @Nullable
    public SlotBinding getBinding() {
        return binding;
    }

    /**
     * Sets a binding (or empty content) and clears any icon.
     * @param binding the binding, or null
     */
    public void setBinding(@Nullable SlotBinding binding) {
        this.binding = binding;
        this.item = null;
    }

    /**
     * Gets the parent node.
     *
     * @return the parent node, may be null
     */
    @Nullable
    public RenderNode getParent() {
        return parent;
    }

    /**
     * Sets the parent node.
     *
     * @param parent the parent node
     */
    void setParent(@Nullable RenderNode parent) {
        this.parent = parent;
    }

    /**
     * Gets the list of child nodes (unmodifiable).
     *
     * @return the list of child nodes
     */
    @NotNull
    public List<RenderNode> getChildren() {
        return Collections.unmodifiableList(children);
    }

    /**
     * Adds a child node.
     *
     * @param child the child node
     */
    public void addChild(@NotNull RenderNode child) {
        children.add(child);
        child.setParent(this);
    }

    /**
     * Removes a child node.
     *
     * @param child the child node
     */
    public void removeChild(@NotNull RenderNode child) {
        children.remove(child);
        child.setParent(null);
    }

    /**
     * Clears all child nodes.
     */
    public void clearChildren() {
        for (RenderNode child : children) {
            child.setParent(null);
        }
        children.clear();
    }

    /**
     * Gets a metadata value.
     *
     * @param key the key
     * @return the value, or null if it does not exist
     */
    @Nullable
    public Object getMetadata(@NotNull String key) {
        return metadata.get(key);
    }

    /**
     * Sets a metadata value.
     *
     * @param key   the key
     * @param value the value
     */
    public void setMetadata(@NotNull String key, @Nullable Object value) {
        if (value == null) {
            metadata.remove(key);
        } else {
            metadata.put(key, value);
        }
    }

    /**
     * Checks whether this is a leaf node (no child nodes).
     *
     * @return true if this is a leaf node
     */
    public boolean isLeaf() {
        return children.isEmpty();
    }

    /**
     * Checks whether this is a container node (has child nodes).
     *
     * @return true if this is a container node
     */
    public boolean isContainer() {
        return !children.isEmpty();
    }

    /**
     * Gets the list of all leaf nodes (recursively).
     *
     * @return all leaf nodes
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
     * Calculates the number of slots occupied by this subtree.
     *
     * @return the slot count
     */
    public int calculateSlotCount() {
        if (isLeaf()) {
            return item != null || binding != null ? 1 : 0;
        }
        int count = 0;
        for (RenderNode child : children) {
            count += child.calculateSlotCount();
        }
        return count;
    }

    /**
     * Creates a deep copy of this node (excluding its children).
     *
     * @return the new RenderNode
     */
    @NotNull
    public RenderNode copy() {
        RenderNode copy = new RenderNode(key, slotIndex);
        copy.item = item;
        copy.binding = binding;
        copy.metadata.putAll(metadata);
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true; // NOPMD - equals must accept the identical node before value comparison
        if (!(o instanceof RenderNode)) return false;
        RenderNode that = (RenderNode) o;
        // Prefer comparing by key; fall back to slotIndex when there is no key.
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
        return "RenderNode(key=" + keyStr + ", slot=" + slotIndex + ", item=" + item
                + ", binding=" + binding + ")";
    }
}
