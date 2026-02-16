package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * BuildContext 测试。
 */
public class BuildContextTest {

    @Test
    void testRootContextCreation() {
        Player player = mock(Player.class);
        BuildContext context = BuildContext.root(player, "test-gui", 6);

        assertEquals(player, context.getPlayer());
        assertEquals("test-gui", context.getGuiId());
        assertEquals(6, context.getRows());
        assertEquals(54, context.getSize());
        assertNull(context.getParentElement());
    }

    @Test
    void testChildContext() {
        Player player = mock(Player.class);
        BuildContext parent = BuildContext.root(player, "parent", 3);

        // 创建 mock Element
        Element mockElement = mock(Element.class);
        BuildContext child = parent.child(mockElement);

        assertEquals(parent.getPlayer(), child.getPlayer());
        assertEquals(parent.getGuiId(), child.getGuiId());
        assertEquals(mockElement, child.getParentElement());
    }

    @Test
    void testInheritedProperties() {
        Player player = mock(Player.class);
        BuildContext context = BuildContext.root(player, "test", 3);

        // 使用 builder 添加继承属性
        BuildContext modified = context.toBuilder()
                .inheritedProperty("key1", "value1")
                .inheritedProperty("key2", 123)
                .build();

        assertEquals("value1", modified.getInheritedProperty("key1"));
        assertEquals(123, modified.getInheritedProperty("key2"));
        assertTrue(modified.hasInheritedProperty("key1"));
        assertFalse(modified.hasInheritedProperty("nonexistent"));
    }

    @Test
    void testRemoveInheritedProperty() {
        Player player = mock(Player.class);
        BuildContext context = BuildContext.root(player, "test", 3)
                .toBuilder()
                .inheritedProperty("key", "value")
                .build();

        assertTrue(context.hasInheritedProperty("key"));

        BuildContext removed = context.toBuilder()
                .inheritedProperty("key", null)
                .build();

        assertFalse(removed.hasInheritedProperty("key"));
        assertNull(removed.getInheritedProperty("key"));
    }
}
