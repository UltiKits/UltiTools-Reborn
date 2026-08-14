package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import mc.obliviate.inventory.Gui;

/**
 * DeclarativeGui 的点击语义测试。
 * <p>
 * 这里不构造真实实例：DeclarativeGui 的构造函数会创建 GuiRenderer 与 GuiScheduler，
 * 后者需要一个已启用的 UltiTools 实例。CALLS_REAL_METHODS 的 mock 跳过构造函数，
 * 但仍然执行真实的方法体，正好够验证默认返回值。
 */
@DisplayName("DeclarativeGui Click Semantics")
class DeclarativeGuiTest {

    @Test
    @DisplayName("onGuiClick defaults to false so the click stays cancelled")
    void onGuiClickDefaultsToFalse() {
        DeclarativeGui gui = mock(DeclarativeGui.class, CALLS_REAL_METHODS);
        InventoryClickEvent event = mock(InventoryClickEvent.class);

        assertFalse(gui.onGuiClick(event),
                "onGuiClick must default to false. obliviate-invs cancels the event when "
                        + "Gui.onClick returns false and un-cancels it when it returns true, "
                        + "so a true default lets players take items out of every page.");
    }

    @Test
    @DisplayName("The default matches the obliviate-invs base class")
    void defaultMatchesLibraryBaseClass() throws Exception {
        DeclarativeGui gui = mock(DeclarativeGui.class, CALLS_REAL_METHODS);
        Gui libraryGui = mock(Gui.class, CALLS_REAL_METHODS);
        InventoryClickEvent event = mock(InventoryClickEvent.class);

        assertEquals(libraryGui.onClick(event), gui.onGuiClick(event),
                "the hook default must agree with Gui.onClick's own default");
    }

    @Test
    @DisplayName("onClick stays final so the forwarding cannot be re-inverted")
    void onClickIsFinal() throws Exception {
        Method onClick = DeclarativeGui.class.getDeclaredMethod("onClick", InventoryClickEvent.class);

        assertTrue(Modifier.isFinal(onClick.getModifiers()),
                "onClick must stay final: it forwards to onGuiClick, and a subclass that "
                        + "overrode it could silently reintroduce the inverted semantics.");
    }
}
