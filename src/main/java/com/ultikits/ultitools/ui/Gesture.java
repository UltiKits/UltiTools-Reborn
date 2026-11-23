package com.ultikits.ultitools.ui;

import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/** A decoded player gesture on a grid screen. Internal; may change in any release. */
@ApiStatus.Internal
public sealed interface Gesture permits Gesture.Pickup, Gesture.Place, Gesture.SwapWithCursor,
        Gesture.HotbarSwap, Gesture.QuickMoveOut, Gesture.QuickMoveIn, Gesture.Spread {
    /** Left (all) or right (half, rounded up) on a bound slot with an empty cursor. */
    record Pickup(GridSlot slot, boolean half) implements Gesture {
        public Pickup { Objects.requireNonNull(slot, "slot"); }
    }
    /** Left (all) or right (one) with a non-empty cursor on a bound slot holding nothing or a similar item. */
    record Place(GridSlot slot, boolean one) implements Gesture {
        public Place { Objects.requireNonNull(slot, "slot"); }
    }
    /** Left with a non-empty cursor on a bound slot holding a different item. */
    record SwapWithCursor(GridSlot slot) implements Gesture {
        public SwapWithCursor { Objects.requireNonNull(slot, "slot"); }
    }
    /**
     * Number key 1-9 over a bound slot: exchanges the bound item with hotbar slot {@code hotbar} (0..8, Bukkit
     * inventory slot number = hotbar index). Either side may be empty. The cursor is not involved.
     */
    record HotbarSwap(GridSlot slot, int hotbar) implements Gesture {
        /** @throws IllegalArgumentException if hotbar is outside 0..8 */
        public HotbarSwap {
            Objects.requireNonNull(slot, "slot");
            if (hotbar < 0 || hotbar > 8) {
                throw new IllegalArgumentException("hotbar must be in 0..8");
            }
        }
    }
    /** Shift-click on a bound slot: to the viewer's inventory, what fits. */
    record QuickMoveOut(GridSlot slot) implements Gesture {
        public QuickMoveOut { Objects.requireNonNull(slot, "slot"); }
    }
    /** Shift-click on one of the viewer's storage slots ({@code playerSlot} 0..35): into visible EDITABLE bindings. */
    record QuickMoveIn(int playerSlot) implements Gesture { }
    /** Cursor drag over top slots only; {@code one}: one per slot, else split evenly. */
    record Spread(Set<GridSlot> slots, boolean one) implements Gesture {
        public Spread { slots = Set.copyOf(Objects.requireNonNull(slots, "slots")); }
    }
}
