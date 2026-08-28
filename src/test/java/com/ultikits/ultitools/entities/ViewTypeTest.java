package com.ultikits.ultitools.entities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.interfaces.VersionWrapper;

class ViewTypeTest {

    private static UltiTools originalInstance;

    @BeforeAll
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    static void setUp() throws Exception {
        // Save original instance
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        originalInstance = (UltiTools) instanceField.get(null);

        // Mock UltiTools
        UltiTools mockUltiTools = mock(UltiTools.class);
        VersionWrapper mockVersionWrapper = mock(VersionWrapper.class);
        ItemStack mockItemStack = mock(ItemStack.class);

        when(mockUltiTools.i18n(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(mockUltiTools.getVersionWrapper()).thenReturn(mockVersionWrapper);
        when(mockVersionWrapper.getColoredPlaneGlass(any())).thenReturn(mockItemStack);
        when(mockVersionWrapper.getSign()).thenReturn(mockItemStack);
        when(mockVersionWrapper.getEndEye()).thenReturn(mockItemStack);

        // Set mock instance
        instanceField.set(null, mockUltiTools);
    }

    @AfterAll
    static void tearDown() throws Exception {
        // Restore original instance
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, originalInstance);
    }

    @Test
    void testEnumValues() {
        assertNotNull(ViewType.PREVIOUS_BACK_NEXT);
        assertNotNull(ViewType.PREVIOUS_QUIT_NEXT);
        assertNotNull(ViewType.OK_CANCEL);
    }

    @Test
    void testPreviousBackNextProperties() {
        ViewType viewType = ViewType.PREVIOUS_BACK_NEXT;
        // Constructor: (true, false, true, true, Colors.GRAY, Buttons.BACK, 1)
        assertTrue(viewType.isLastLineEnabled());
        assertFalse(viewType.isOkCancelEnabled());
        assertTrue(viewType.isPageButtonEnabled());
        assertTrue(viewType.isAutoAddPage());
        assertEquals(Colors.GRAY, viewType.getBackGroundColor());
        assertEquals(Buttons.BACK, viewType.getMiddleButton());
        assertEquals(1, viewType.getPageNumber());
    }

    @Test
    void testPreviousQuitNextProperties() {
        ViewType viewType = ViewType.PREVIOUS_QUIT_NEXT;
        // Constructor: (true, false, true, true, Colors.GRAY, Buttons.QUIT, 1)
        assertTrue(viewType.isLastLineEnabled());
        assertFalse(viewType.isOkCancelEnabled());
        assertTrue(viewType.isPageButtonEnabled());
        assertTrue(viewType.isAutoAddPage());
        assertEquals(Colors.GRAY, viewType.getBackGroundColor());
        assertEquals(Buttons.QUIT, viewType.getMiddleButton());
        assertEquals(1, viewType.getPageNumber());
    }

    @Test
    void testOkCancelProperties() {
        ViewType viewType = ViewType.OK_CANCEL;
        // Constructor: (true, true, false, false, Colors.GRAY, null, 0)
        assertTrue(viewType.isLastLineEnabled());
        assertTrue(viewType.isOkCancelEnabled());
        assertFalse(viewType.isPageButtonEnabled());
        assertFalse(viewType.isAutoAddPage());
        assertEquals(Colors.GRAY, viewType.getBackGroundColor());
        assertNull(viewType.getMiddleButton());
        assertEquals(0, viewType.getPageNumber());
    }
}
