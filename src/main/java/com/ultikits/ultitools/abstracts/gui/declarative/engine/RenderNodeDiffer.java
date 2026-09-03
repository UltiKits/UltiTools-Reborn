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
 * RenderNodeDiffer implements the diff algorithm for the RenderNode tree.
 * <p>
 * The algorithm is based on comparing keys:
 * <ul>
 * <li>if an old node and a new node share the same key, they are treated as the same node
 * (possibly updated)</li>
 * <li>without a key, slotIndex is used for the comparison instead</li>
 * <li>added, removed, and updated nodes are all recorded in the DiffResult</li>
 * </ul>
 *
 * <p><strong>Algorithm steps:</strong></p>
 * <ol>
 * <li>build a key -> node map for the old nodes</li>
 * <li>iterate the new node list, matching against the old nodes</li>
 * <li>record matched nodes (update or move)</li>
 * <li>record unmatched new nodes (added)</li>
 * <li>record unmatched old nodes (removed)</li>
 * </ol>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class RenderNodeDiffer {

    /**
     * Compares two sets of RenderNodes and returns the difference.
     *
     * @param oldNodes the old list of RenderNodes
     * @param newNodes the new list of RenderNodes
     * @return a DiffResult holding every change
     */
    @NotNull
    public DiffResult diff(@NotNull List<RenderNode> oldNodes, @NotNull List<RenderNode> newNodes) {
        DiffResult.Builder builder = DiffResult.builder();
        Map<NodeKey, RenderNode> oldNodeMap = new HashMap<>();

        // Build the old-node map
        for (RenderNode node : oldNodes) {
            oldNodeMap.put(NodeKey.from(node), node);
        }

        // Track which old nodes have been matched
        Set<RenderNode> matchedOldNodes = new HashSet<>();

        // Iterate the new nodes
        for (RenderNode newNode : newNodes) {
            RenderNode oldNode = oldNodeMap.get(NodeKey.from(newNode));

            if (oldNode == null) {
                // A new node
                if (newNode.getIcon() != null) {
                    builder.addAdded(newNode);
                }
            } else {
                // Matched to an old node
                matchedOldNodes.add(oldNode);

                if (hasChanged(oldNode, newNode)) {
                    builder.addUpdated(oldNode, newNode);
                }

                if (oldNode.getSlotIndex() != newNode.getSlotIndex()) {
                    builder.addMoved(newNode, oldNode.getSlotIndex(), newNode.getSlotIndex());
                }
            }
        }

        // Find the removed nodes
        for (RenderNode oldNode : oldNodes) {
            if (!matchedOldNodes.contains(oldNode) && oldNode.getIcon() != null) {
                builder.addRemoved(oldNode);
            }
        }

        return builder.build();
    }

    /**
     * Compares a single node for changes.
     *
     * @param oldNode the old node
     * @param newNode the new node
     * @return true if it changed
     */
    private boolean hasChanged(@NotNull RenderNode oldNode, @NotNull RenderNode newNode) {
        Icon oldIcon = oldNode.getIcon();
        Icon newIcon = newNode.getIcon();

        if (oldIcon == null || newIcon == null) {
            return oldIcon != newIcon;
        }

        return !iconsEqual(oldIcon, newIcon);
    }

    /**
     * Compares two Icons for equality.
     * <p>
     * Note: this comparison is based on visual effect, not object identity.
     *
     * @param a the first Icon
     * @param b the second Icon
     * @return true if they are equal
     */
    private boolean iconsEqual(@NotNull Icon a, @NotNull Icon b) {
        // Compare the ItemStack
        if (!a.getItem().equals(b.getItem())) {
            return false;
        }

        // Compare the display name
        ItemMeta aMeta = a.getItem().getItemMeta();
        Component aName = (aMeta != null && aMeta.hasDisplayName()) ? aMeta.displayName() : null;

        ItemMeta bMeta = b.getItem().getItemMeta();
        Component bName = (bMeta != null && bMeta.hasDisplayName()) ? bMeta.displayName() : null;

        return Objects.equals(aName, bName);
    }

    /**
     * The key used to identify a RenderNode.
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
            // Prefer slotKey; fall back to slotIndex when there is none
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
