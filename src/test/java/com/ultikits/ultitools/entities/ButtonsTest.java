package com.ultikits.ultitools.entities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.UltiTools;

class ButtonsTest {

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

        when(mockUltiTools.i18n(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

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
        assertNotNull(Buttons.PREVIOUS);
        assertNotNull(Buttons.NEXT);
        assertNotNull(Buttons.BACK);
        assertNotNull(Buttons.QUIT);
        assertNotNull(Buttons.OK);
        assertNotNull(Buttons.CANCEL);
    }

    @Test
    void testProperties() {
        // Since we mocked i18n to return the key, we expect the key as name
        assertEquals("上一页", Buttons.PREVIOUS.getName());
        assertNotNull(Buttons.PREVIOUS.getItemStack());

        assertEquals("下一页", Buttons.NEXT.getName());
        assertNotNull(Buttons.NEXT.getItemStack());
        
        assertEquals("返回", Buttons.BACK.getName());
        assertNotNull(Buttons.BACK.getItemStack());
        
        assertEquals("退出", Buttons.QUIT.getName());
        assertNotNull(Buttons.QUIT.getItemStack());
        
        assertEquals("确认", Buttons.OK.getName());
        assertNotNull(Buttons.OK.getItemStack());
        
        assertEquals("取消", Buttons.CANCEL.getName());
        assertNotNull(Buttons.CANCEL.getItemStack());
    }
}
