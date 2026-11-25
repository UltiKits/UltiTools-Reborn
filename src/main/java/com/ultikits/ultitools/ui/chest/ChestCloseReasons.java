package com.ultikits.ultitools.ui.chest;

import com.ultikits.ultitools.ui.CloseReason;
import java.util.Objects;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.jetbrains.annotations.ApiStatus;

/** Maps server-side screen closes to the existing UI result vocabulary. */
@ApiStatus.Internal
public final class ChestCloseReasons {
    private ChestCloseReasons() { /* Static mapping. */ }

    /** Maps a settled close; CANT_USE must first allow a same-tick quit to select DISCONNECTED. */
    public static CloseReason map(InventoryCloseEvent.Reason reason) {
        Objects.requireNonNull(reason, "reason");
        return switch (reason) {
            case PLAYER -> CloseReason.PLAYER;
            case DISCONNECT -> CloseReason.DISCONNECTED;
            default -> CloseReason.INTERRUPTED;
        };
    }
}
