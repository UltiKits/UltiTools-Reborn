package com.ultikits.ultitools.entities;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ColorsTest {

    @Test
    void testEnumValues() {
        assertEquals(0, Colors.WHITE.getCode());
        assertEquals(15, Colors.BLACK.getCode());
        assertEquals(14, Colors.RED.getCode());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(Colors.class)
    void everyColourMapsToItsNativeMaterial(Colors color) {
        assertEquals(org.bukkit.Material.valueOf(color.name() + "_STAINED_GLASS_PANE"),
                color.getGlassPaneMaterial());
    }

    @Test
    void testEnumCount() {
        assertEquals(16, Colors.values().length);
    }
}
