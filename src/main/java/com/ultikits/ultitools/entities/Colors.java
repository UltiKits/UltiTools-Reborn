package com.ultikits.ultitools.entities;

import org.bukkit.Material;

public enum Colors {
    WHITE((short) 0, Material.WHITE_STAINED_GLASS_PANE),
    ORANGE((short) 1, Material.ORANGE_STAINED_GLASS_PANE),
    MAGENTA((short) 2, Material.MAGENTA_STAINED_GLASS_PANE),
    LIGHT_BLUE((short) 3, Material.LIGHT_BLUE_STAINED_GLASS_PANE),
    YELLOW((short) 4, Material.YELLOW_STAINED_GLASS_PANE),
    LIME((short) 5, Material.LIME_STAINED_GLASS_PANE),
    PINK((short) 6, Material.PINK_STAINED_GLASS_PANE),
    GRAY((short) 7, Material.GRAY_STAINED_GLASS_PANE),
    LIGHT_GRAY((short) 8, Material.LIGHT_GRAY_STAINED_GLASS_PANE),
    CYAN((short) 9, Material.CYAN_STAINED_GLASS_PANE),
    PURPLE((short) 10, Material.PURPLE_STAINED_GLASS_PANE),
    BLUE((short) 11, Material.BLUE_STAINED_GLASS_PANE),
    BROWN((short) 12, Material.BROWN_STAINED_GLASS_PANE),
    GREEN((short) 13, Material.GREEN_STAINED_GLASS_PANE),
    RED((short) 14, Material.RED_STAINED_GLASS_PANE),
    BLACK((short) 15, Material.BLACK_STAINED_GLASS_PANE);

    private final short code;
    private final Material glassPaneMaterial;

    Colors(short code, Material glassPaneMaterial) {
        this.code = code;
        this.glassPaneMaterial = glassPaneMaterial;
    }

    public short getCode() {
        return code;
    }

    /** Returns the native glass pane material for this colour. */
    public Material getGlassPaneMaterial() {
        return glassPaneMaterial;
    }
}
