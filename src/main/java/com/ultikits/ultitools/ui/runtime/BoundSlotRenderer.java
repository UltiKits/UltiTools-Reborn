package com.ultikits.ultitools.ui.runtime;

import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.ui.GridSlot;
import java.util.Map;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** Optional for a backend that draws bound slots (the chest backend). Internal. */
@ApiStatus.Internal
public interface BoundSlotRenderer {
    /** Draws {@code picture} (copies) on {@code session}'s screen. Called in the viewer's entity task. */
    void redraw(GridSession session, Map<GridSlot, ItemStack> picture);
}
