package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderObjectElement;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import be.seeseemelk.mockbukkit.MockBukkit;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TextButton 测试。
 */
public class TextButtonTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void testTextButtonCreation() {
        TextButton button = TextButton.builder()
                .text("Click Me")
                .color("GREEN")
                .slot(13)
                .lore("Line 1", "Line 2")
                .build();

        assertEquals("Click Me", button.getText());
        assertEquals("GREEN", button.getColor());
        assertEquals(13, button.getSlot());
        assertArrayEquals(new String[] { "Line 1", "Line 2" }, button.getLore());
    }

    @Test
    void testTextButtonDefaults() {
        TextButton button = TextButton.builder().build();

        assertEquals("", button.getText());
        assertEquals("WHITE", button.getColor());
        assertEquals(0, button.getSlot());
        assertNull(button.getLore());
    }

    @Test
    void testTextButtonWithKey() {
        TextButton button = TextButton.builder()
                .key("confirm-btn")
                .build();

        assertNotNull(button.getKey());
        assertEquals("confirm-btn", button.getKey().getValue());
    }

    @Test
    void testTextButtonWithClickHandler() {
        AtomicBoolean clicked = new AtomicBoolean(false);

        TextButton button = TextButton.builder()
                .text("Test")
                .onClick(() -> clicked.set(true))
                .build();

        Consumer<?> handler = button.getClickHandler();
        assertNotNull(handler);
    }

    @Test
    void testTextButtonWithSlotKeyBuilder() {
        TextButton button = TextButton.builder()
                .text("Test")
                .key("my-button")
                .build();

        assertNotNull(button.getKey());
    }
}
