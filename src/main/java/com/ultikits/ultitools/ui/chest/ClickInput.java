package com.ultikits.ultitools.ui.chest;

import com.ultikits.ultitools.ui.GridSlot;
import java.util.Objects;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.jetbrains.annotations.ApiStatus;

/** Immutable input; {@code slotEmpty} describes the container, not the screen copy. */
@ApiStatus.Internal
public record ClickInput(InventoryAction action, ClickType click, SlotKind kind, CursorState cursor,
                         boolean slotEmpty, GridSlot slot, int playerSlot, int hotbar) {
    public ClickInput {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(click, "click");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(slot, "slot");
    }
}
