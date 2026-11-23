package com.ultikits.ultitools.entities;

import org.bukkit.NamespacedKey;

/** Sound keys resolved through Paper's native sound registry. */
public enum Sounds {
    BLOCK_NOTE_BLOCK_HAT("block.note_block.hat"),
    BLOCK_CHEST_OPEN("block.chest.open"),
    BLOCK_CHEST_LOCKED("block.chest.locked"),
    ENTITY_ENDERMAN_TELEPORT("entity.enderman.teleport"),
    BLOCK_WET_GRASS_BREAK("block.wet_grass.break"),
    UI_TOAST_OUT("ui.toast.out"),
    BLOCK_NOTE_BLOCK_CHIME("block.note_block.chime"),
    BLOCK_NOTE_BLOCK_BELL("block.note_block.bell"),
    ITEM_BOOK_PAGE_TURN("item.book.page_turn"),
    BLOCK_CHEST_CLOSE("block.chest.close");

    private final NamespacedKey key;

    Sounds(String key) {
        this.key = NamespacedKey.minecraft(key);
    }

    public NamespacedKey getKey() {
        return key;
    }
}
