package com.ultikits.ultitools.abstracts.gui.declarative.core;

import mc.obliviate.inventory.Icon;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RenderNode 测试。
 */
public class RenderNodeTest {

    @Test
    void testRenderNodeCreation() {
        SlotKey key = SlotKey.of("test");
        RenderNode node = new RenderNode(key, 10);

        assertEquals(key, node.getKey());
        assertEquals(10, node.getSlotIndex());
        assertNull(node.getIcon());
        assertTrue(node.isLeaf());
    }

    @Test
    void testRenderNodeWithIcon() {
        ItemStack item = new ItemStack(Material.DIAMOND);
        Icon icon = new Icon(item);
        RenderNode node = new RenderNode(null, 5, icon);

        assertEquals(5, node.getSlotIndex());
        assertEquals(icon, node.getIcon());
    }

    @Test
    void testChildManagement() {
        RenderNode parent = new RenderNode(SlotKey.of("parent"), -1);
        RenderNode child1 = new RenderNode(SlotKey.of("child1"), 1);
        RenderNode child2 = new RenderNode(SlotKey.of("child2"), 2);

        assertTrue(parent.isLeaf());
        assertTrue(parent.isContainer() == false);

        parent.addChild(child1);
        parent.addChild(child2);

        assertFalse(parent.isLeaf());
        assertTrue(parent.isContainer());
        assertEquals(2, parent.getChildren().size());
        assertEquals(parent, child1.getParent());
        assertEquals(parent, child2.getParent());
    }

    @Test
    void testRemoveChild() {
        RenderNode parent = new RenderNode(null, -1);
        RenderNode child = new RenderNode(null, 1);

        parent.addChild(child);
        assertEquals(1, parent.getChildren().size());

        parent.removeChild(child);
        assertEquals(0, parent.getChildren().size());
        assertNull(child.getParent());
    }

    @Test
    void testClearChildren() {
        RenderNode parent = new RenderNode(null, -1);
        parent.addChild(new RenderNode(null, 1));
        parent.addChild(new RenderNode(null, 2));

        parent.clearChildren();
        assertEquals(0, parent.getChildren().size());
    }

    @Test
    void testGetAllLeaves() {
        RenderNode root = new RenderNode(null, -1);
        RenderNode child1 = new RenderNode(SlotKey.of("c1"), 1);
        RenderNode child2 = new RenderNode(null, -1);
        RenderNode grandchild = new RenderNode(SlotKey.of("gc"), 2);

        root.addChild(child1);
        root.addChild(child2);
        child2.addChild(grandchild);

        List<RenderNode> leaves = root.getAllLeaves();
        assertEquals(2, leaves.size());
        assertTrue(leaves.contains(child1));
        assertTrue(leaves.contains(grandchild));
    }

    @Test
    void testCalculateSlotCount() {
        ItemStack item = new ItemStack(Material.STONE);
        Icon icon = new Icon(item);

        RenderNode leafWithIcon = new RenderNode(null, 1, icon);
        RenderNode leafWithoutIcon = new RenderNode(null, 2);

        assertEquals(1, leafWithIcon.calculateSlotCount());
        assertEquals(0, leafWithoutIcon.calculateSlotCount());
    }

    @Test
    void testMetadata() {
        RenderNode node = new RenderNode(null, 0);
        node.setMetadata("key", "value");

        assertEquals("value", node.getMetadata("key"));

        node.setMetadata("key", null);
        assertNull(node.getMetadata("key"));
    }

    @Test
    void testCopy() {
        SlotKey key = SlotKey.of("original");
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        Icon icon = new Icon(item);
        RenderNode original = new RenderNode(key, 10, icon);
        original.setMetadata("meta", "value");
        java.util.function.Consumer<org.bukkit.event.inventory.InventoryClickEvent> handler = event -> { };
        original.setClickHandler(handler);

        RenderNode copy = original.copy();

        // 05-11 plan Task 3: extends the plan's original assertions (key, slotIndex,
        // icon-not-null, metadata value) with click handler coverage — copy() has a
        // production caller for the first time as of this plan (GuiRenderer.
        // collectRenderNodesRecursive), so its full field coverage matters now in a way
        // it didn't when it was dead code.
        assertEquals(original.getKey(), copy.getKey());
        assertEquals(original.getSlotIndex(), copy.getSlotIndex());
        assertNotNull(copy.getIcon());
        assertEquals("value", copy.getMetadata("meta"));
        assertSame(handler, copy.getClickHandler(),
                "copy() must carry the click handler — GuiRenderer.updateClickHandlers() reads "
                        + "it off the collected (copied) nodes, not the live ones");
    }

    @Test
    void testCopyIsIndependentOfLaterMutationOnTheOriginal() {
        // This is the exact property GuiRenderer.collectRenderNodesRecursive relies on:
        // a snapshot taken this frame must not be retroactively altered by next frame's
        // in-place mutation of the ORIGINAL (live) RenderNode via its setters — see
        // ItemDisplayElement.updateRenderNode(), which reassigns slotIndex/icon on the
        // SAME cached RenderNode instance every rebuild.
        SlotKey key = SlotKey.of("original");
        RenderNode original = new RenderNode(key, 0, new Icon(new ItemStack(Material.DIAMOND)));
        original.setMetadata("meta", "before");
        java.util.function.Consumer<org.bukkit.event.inventory.InventoryClickEvent> handlerBefore = event -> { };
        original.setClickHandler(handlerBefore);

        RenderNode copy = original.copy();

        // Mutate every copyable field on the ORIGINAL after the copy was taken.
        Icon newIcon = new Icon(new ItemStack(Material.GOLD_INGOT));
        original.setIcon(newIcon);
        original.setSlotIndex(5);
        original.setMetadata("meta", "after");
        java.util.function.Consumer<org.bukkit.event.inventory.InventoryClickEvent> handlerAfter = event -> { };
        original.setClickHandler(handlerAfter);

        assertNotSame(newIcon, copy.getIcon(),
                "the copy's icon must not change when the original's icon reference is reassigned");
        assertEquals(0, copy.getSlotIndex(),
                "the copy's slotIndex must not change when the original's is reassigned");
        assertEquals("before", copy.getMetadata("meta"),
                "the copy's metadata must not change when the original's is reassigned");
        assertSame(handlerBefore, copy.getClickHandler(),
                "the copy's click handler must not change when the original's is reassigned");
    }

    @Test
    void testEquality() {
        SlotKey key = SlotKey.of("same");
        RenderNode node1 = new RenderNode(key, 1);
        RenderNode node2 = new RenderNode(key, 2); // 不同 slot
        RenderNode node3 = new RenderNode(null, 1); // 无 key，相同 slot

        // 有 key 时，key 相同则相等
        assertEquals(node1, node2);

        // 无 key 时，slot 相同则相等
        assertEquals(node1, node3); // key 优先
    }
}
