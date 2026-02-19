package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.abstracts.gui.declarative.core.DiffResult;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderNode;
import com.ultikits.ultitools.abstracts.gui.declarative.core.SlotKey;
import mc.obliviate.inventory.Icon;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RenderNode Diff 算法测试。
 */
public class RenderNodeDifferTest {

    private final RenderNodeDiffer differ = new RenderNodeDiffer();

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void testEmptyDiff() {
        List<RenderNode> oldNodes = new ArrayList<>();
        List<RenderNode> newNodes = new ArrayList<>();

        DiffResult result = differ.diff(oldNodes, newNodes);

        assertTrue(result.isEmpty());
        assertEquals(0, result.getChangeCount());
    }

    @Test
    void testAddNodes() {
        List<RenderNode> oldNodes = new ArrayList<>();
        List<RenderNode> newNodes = new ArrayList<>();
        newNodes.add(createNode("key1", 0, Material.DIAMOND));
        newNodes.add(createNode("key2", 1, Material.GOLD_INGOT));

        DiffResult result = differ.diff(oldNodes, newNodes);

        assertEquals(2, result.getAdded().size());
        assertTrue(result.getRemoved().isEmpty());
        assertTrue(result.getUpdated().isEmpty());
    }

    @Test
    void testRemoveNodes() {
        List<RenderNode> oldNodes = new ArrayList<>();
        oldNodes.add(createNode("key1", 0, Material.DIAMOND));
        oldNodes.add(createNode("key2", 1, Material.GOLD_INGOT));
        List<RenderNode> newNodes = new ArrayList<>();

        DiffResult result = differ.diff(oldNodes, newNodes);

        assertTrue(result.getAdded().isEmpty());
        assertEquals(2, result.getRemoved().size());
        assertTrue(result.getUpdated().isEmpty());
    }

    @Test
    void testUpdateNodes() {
        List<RenderNode> oldNodes = new ArrayList<>();
        oldNodes.add(createNode("key1", 0, Material.DIAMOND));

        List<RenderNode> newNodes = new ArrayList<>();
        newNodes.add(createNode("key1", 0, Material.GOLD_INGOT)); // 相同 key，不同 item

        DiffResult result = differ.diff(oldNodes, newNodes);

        assertTrue(result.getAdded().isEmpty());
        assertTrue(result.getRemoved().isEmpty());
        assertEquals(1, result.getUpdated().size());
    }

    @Test
    void testMoveNodes() {
        List<RenderNode> oldNodes = new ArrayList<>();
        oldNodes.add(createNode("key1", 0, Material.DIAMOND));

        List<RenderNode> newNodes = new ArrayList<>();
        newNodes.add(createNode("key1", 5, Material.DIAMOND)); // 相同 key，不同 slot

        DiffResult result = differ.diff(oldNodes, newNodes);

        // 移动被视为更新 + 移动
        assertEquals(1, result.getMoved().size());
        DiffResult.RenderNodeMove move = result.getMoved().get(0);
        assertEquals(0, move.getFromSlot());
        assertEquals(5, move.getToSlot());
    }

    @Test
    void testMixedChanges() {
        List<RenderNode> oldNodes = new ArrayList<>();
        oldNodes.add(createNode("keep", 0, Material.DIAMOND));
        oldNodes.add(createNode("remove", 1, Material.GOLD_INGOT));

        List<RenderNode> newNodes = new ArrayList<>();
        newNodes.add(createNode("keep", 0, Material.DIAMOND)); // 保持不变
        newNodes.add(createNode("add", 2, Material.IRON_INGOT)); // 新增

        DiffResult result = differ.diff(oldNodes, newNodes);

        assertEquals(1, result.getAdded().size());
        assertEquals(1, result.getRemoved().size());
        assertEquals(0, result.getUpdated().size());
    }

    @Test
    void testNoChangeForIdenticalNodes() {
        List<RenderNode> oldNodes = new ArrayList<>();
        oldNodes.add(createNode("key1", 0, Material.DIAMOND));

        List<RenderNode> newNodes = new ArrayList<>();
        newNodes.add(createNode("key1", 0, Material.DIAMOND)); // 完全相同

        DiffResult result = differ.diff(oldNodes, newNodes);

        assertTrue(result.isEmpty());
    }

    @Test
    void testDiffWithSlotIndexOnly() {
        // 没有 key，使用 slotIndex 匹配
        List<RenderNode> oldNodes = new ArrayList<>();
        RenderNode node1 = new RenderNode(null, 0);
        node1.setIcon(new Icon(new ItemStack(Material.DIAMOND)));
        oldNodes.add(node1);

        List<RenderNode> newNodes = new ArrayList<>();
        RenderNode node2 = new RenderNode(null, 0);
        node2.setIcon(new Icon(new ItemStack(Material.GOLD_INGOT)));
        newNodes.add(node2);

        DiffResult result = differ.diff(oldNodes, newNodes);

        assertEquals(1, result.getUpdated().size());
    }

    private RenderNode createNode(String key, int slot, Material material) {
        RenderNode node = new RenderNode(SlotKey.of(key), slot);
        node.setIcon(new Icon(new ItemStack(material)));
        return node;
    }
}
