package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.Element;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Container 测试。
 */
public class ContainerTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void testEmptyContainer() {
        Container container = Container.builder().build();

        assertNotNull(container.getChildren());
        assertTrue(container.getChildren().isEmpty());
    }

    @Test
    void testContainerWithChildren() {
        ItemStack item1 = new ItemStack(Material.DIAMOND);
        ItemStack item2 = new ItemStack(Material.GOLD_INGOT);

        Container container = Container.builder()
                .child(ItemDisplay.builder(item1).build())
                .child(ItemDisplay.builder(item2).build())
                .build();

        assertEquals(2, container.getChildren().size());
    }

    @Test
    void testContainerWithMultipleChildrenMethods() {
        Widget child1 = ItemDisplay.builder(new ItemStack(Material.STONE)).build();
        Widget child2 = ItemDisplay.builder(new ItemStack(Material.DIRT)).build();
        Widget child3 = ItemDisplay.builder(new ItemStack(Material.GRASS_BLOCK)).build();

        Container container = Container.builder()
                .child(child1)
                .children(child2, child3)
                .build();

        assertEquals(3, container.getChildren().size());
    }

    @Test
    void testContainerWithKey() {
        Container container = Container.builder()
                .key("main-container")
                .build();

        assertNotNull(container.getKey());
        assertEquals("main-container", container.getKey().getValue());
    }

    @Test
    void testContainerCreatesElement() {
        Container container = Container.builder()
                .child(ItemDisplay.builder(new ItemStack(Material.DIAMOND)).build())
                .build();

        Element element = container.createElement();
        assertNotNull(element);
    }

    @Test
    void testContainerChildrenAreImmutable() {
        Container container = Container.builder()
                .child(ItemDisplay.builder(new ItemStack(Material.STONE)).build())
                .build();

        // getChildren 返回的列表不应该能被修改
        assertThrows(UnsupportedOperationException.class, () -> {
            container.getChildren().add(ItemDisplay.builder(new ItemStack(Material.DIRT)).build());
        });
    }
}
