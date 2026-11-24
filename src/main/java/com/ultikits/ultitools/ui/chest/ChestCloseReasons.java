package com.ultikits.ultitools.ui.chest;

import com.ultikits.ultitools.ui.CloseReason;
import java.util.Objects;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.jetbrains.annotations.ApiStatus;

/** Maps server-side screen closes to the existing UI result vocabulary. */
@ApiStatus.Internal
public final class ChestCloseReasons {
    private ChestCloseReasons() { /* Static mapping. */ }

    /** A close not caused by the runtime is a viewer-side close, except for disconnect. */
    public static CloseReason map(InventoryCloseEvent.Reason reason) {
        Objects.requireNonNull(reason, "reason");
        return reason == InventoryCloseEvent.Reason.DISCONNECT ? CloseReason.DISCONNECTED : CloseReason.PLAYER;
    }
}
