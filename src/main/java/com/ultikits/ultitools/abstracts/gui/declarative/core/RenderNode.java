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
import java.util.function.Consumer;

/**
 * RenderNode is a node in the virtual render tree, representing one slot to be rendered.
 * <p>
 * RenderNode is a framework-internal object used to:
 * <ul>
 *   <li>describe what each slot should display (Icon)</li>
 *   <li>record the slot's position (slotIndex)</li>
 *   <li>support stable identification (slotKey) for diffing</li>
 *   <li>organize into a tree structure to support layout calculation</li>
 * </ul>
 * <p>
 * <b>Note:</b> RenderNode never touches the Bukkit Inventory directly;
 * it is only a virtual representation, translated into actual Inventory operations by
 * {@link GuiRenderer}.
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class RenderNode {

    /**
     * The slot's unique identifier, used by the diff algorithm to recognize the same node.
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

    /**
     * The Icon to display in this slot.
     * If null, this slot should be empty.
     */
    @Nullable
    private Icon icon;

    /**
     * The click event handler.
     */
    @Nullable
    private Consumer<InventoryClickEvent> clickHandler;

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
     * @param icon      the icon
     */
    public RenderNode(@Nullable SlotKey key, int slotIndex, @Nullable Icon icon) {
        this(key, slotIndex);
        this.icon = icon;
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

    /**
     * Gets the icon.
     *
     * @return the Icon, may be null
     */
    @Nullable
    public Icon getIcon() {
        return icon;
    }

    /**
     * Sets the icon.
     *
     * @param icon the icon
     */
    public void setIcon(@Nullable Icon icon) {
        this.icon = icon;
    }

    /**
     * Gets the click handler.
     *
     * @return the click handler, may be null
     */
    @Nullable
    public Consumer<InventoryClickEvent> getClickHandler() {
        return clickHandler;
    }

    /**
     * Sets the click handler.
     *
     * @param clickHandler the click handler
     */
    public void setClickHandler(@Nullable Consumer<InventoryClickEvent> clickHandler) {
        this.clickHandler = clickHandler;
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
            return icon != null ? 1 : 0;
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
        String iconStr = icon != null ? "Icon" : "null";
        return "RenderNode(key=" + keyStr + ", slot=" + slotIndex + ", icon=" + iconStr + ")";
    }
}
