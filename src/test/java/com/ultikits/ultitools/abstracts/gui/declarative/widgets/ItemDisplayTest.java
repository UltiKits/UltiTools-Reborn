package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderObjectElement;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderNode;
import mc.obliviate.inventory.Icon;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import be.seeseemelk.mockbukkit.MockBukkit;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ItemDisplay 测试。
 */
public class ItemDisplayTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void testItemDisplayCreation() {
        ItemStack item = new ItemStack(Material.DIAMOND);
        ItemDisplay display = ItemDisplay.builder(item)
                .slot(10)
                .name("Test Item")
                .lore("Line 1", "Line 2")
                .build();

        assertEquals(10, display.getSlot());
        assertEquals("Test Item", display.getDisplayName());
        assertArrayEquals(new String[] { "Line 1", "Line 2" }, display.getLore());
    }

    @Test
    void testItemDisplayWithKey() {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemDisplay display = ItemDisplay.builder(item)
                .key("my-item")
                .build();

        assertNotNull(display.getKey());
        assertEquals("my-item", display.getKey().getValue());
    }

    @Test
    void testItemDisplayWithClickHandler() {
        ItemStack item = new ItemStack(Material.STONE);
        AtomicBoolean clicked = new AtomicBoolean(false);

        ItemDisplay display = ItemDisplay.builder(item)
                .onClick(() -> clicked.set(true))
                .build();

        Consumer<InventoryClickEvent> handler = display.getClickHandler();
        assertNotNull(handler);
        handler.accept(null);
        assertTrue(clicked.get());
    }

    @Test
    void testItemDisplayElementCreatesRenderNode() {
        ItemStack item = new ItemStack(Material.IRON_INGOT);
        ItemDisplay display = ItemDisplay.builder(item)
                .slot(5)
                // .name("Iron") // Removed name test to avoid MockBukkit Adventure issue
                .build();

        RenderObjectElement element = display.createElement();
        assertNotNull(element);

        RenderNode node = element.getRenderNode();
        assertNotNull(node);
        assertEquals(5, node.getSlotIndex());
        assertNotNull(node.getIcon());
    }

    @Test
    void testItemDisplayBuilderDefaults() {
        ItemStack item = new ItemStack(Material.COAL);
        ItemDisplay display = ItemDisplay.builder(item).build();

        assertEquals(0, display.getSlot()); // 默认 slot 为 0
        assertNull(display.getDisplayName());
        assertNull(display.getLore());
        assertNull(display.getKey());
        assertNull(display.getClickHandler());
    }
}
