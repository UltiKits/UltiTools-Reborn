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

        RenderNode copy = original.copy();

        assertEquals(original.getKey(), copy.getKey());
        assertEquals(original.getSlotIndex(), copy.getSlotIndex());
        assertNotNull(copy.getIcon());
        assertEquals("value", copy.getMetadata("meta"));
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
