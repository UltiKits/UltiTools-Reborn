package com.ultikits.ultitools.ui.chest;

import com.ultikits.ultitools.ui.ClickKind;
import com.ultikits.ultitools.ui.Gesture;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.jetbrains.annotations.ApiStatus;

/** Translates inventory inputs without accessing or changing any inventory. */
@ApiStatus.Internal
public final class ClickDecoder {
    private static final ClickDecision ALLOW = new ClickDecision.Allow();
    private static final ClickDecision CANCEL = new ClickDecision.Cancel();

    private ClickDecoder() { /* Static pure decoder. */ }

    /** Returns exactly one decision for an input. Unrecognised item actions never move bound items. */
    public static ClickDecision decode(ClickInput input) {
        return switch (input.kind()) {
            case PLAYER_STORAGE -> storage(input);
            case OUTSIDE -> outside(input.action());
            case ICON -> input.click() == ClickType.DOUBLE_CLICK
                    ? CANCEL : new ClickDecision.Report(input.slot(), clickKind(input.click()));
            case BOUND_EDITABLE, BOUND_VIEW -> bound(input);
            default -> CANCEL;
        };
    }

    private static ClickDecision storage(ClickInput input) {
        return switch (input.action()) {
            case NOTHING, PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE,
                    PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR,
                    HOTBAR_SWAP, DROP_ALL_SLOT, DROP_ONE_SLOT -> ALLOW;
            case MOVE_TO_OTHER_INVENTORY -> input.playerSlot() >= 0 && input.playerSlot() < 36
                    ? apply(new Gesture.QuickMoveIn(input.playerSlot())) : CANCEL;
            default -> CANCEL;
        };
    }

    private static ClickDecision outside(InventoryAction action) {
        return switch (action) {
            case NOTHING, DROP_ALL_CURSOR, DROP_ONE_CURSOR -> ALLOW;
            default -> CANCEL;
        };
    }

    private static boolean ordinary(InventoryAction action) {
        return switch (action) {
            case NOTHING, PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE,
                    PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR, DROP_ALL_CURSOR,
                    DROP_ONE_CURSOR, DROP_ALL_SLOT, DROP_ONE_SLOT, MOVE_TO_OTHER_INVENTORY,
                    HOTBAR_MOVE_AND_READD, HOTBAR_SWAP, CLONE_STACK, COLLECT_TO_CURSOR, UNKNOWN -> true;
            default -> false;
        };
    }

    private static ClickDecision bound(ClickInput input) {
        if (!ordinary(input.action())) { return CANCEL; }
        return switch (input.click()) {
            case NUMBER_KEY -> hotbar(input);
            case SHIFT_LEFT, SHIFT_RIGHT -> input.action() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                    ? apply(new Gesture.QuickMoveOut(input.slot())) : CANCEL;
            case LEFT, RIGHT -> cursor(input);
            default -> CANCEL;
        };
    }

    private static ClickDecision hotbar(ClickInput input) {
        if (input.hotbar() < 0 || input.hotbar() > 8) { return CANCEL; }
        return switch (input.action()) {
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> apply(new Gesture.HotbarSwap(input.slot(), input.hotbar()));
            default -> CANCEL;
        };
    }

    private static ClickDecision cursor(ClickInput input) {
        boolean right = input.click() == ClickType.RIGHT;
        if (input.cursor() == CursorState.EMPTY) { return apply(new Gesture.Pickup(input.slot(), right)); }
        if (input.cursor() == CursorState.SIMILAR || input.slotEmpty()) {
            return apply(new Gesture.Place(input.slot(), right));
        }
        return right ? CANCEL : apply(new Gesture.SwapWithCursor(input.slot()));
    }

    private static ClickDecision apply(Gesture gesture) { return new ClickDecision.Apply(gesture); }

    private static ClickKind clickKind(ClickType click) {
        return switch (click) {
            case LEFT -> ClickKind.LEFT;
            case RIGHT -> ClickKind.RIGHT;
            case SHIFT_LEFT -> ClickKind.SHIFT_LEFT;
            case SHIFT_RIGHT -> ClickKind.SHIFT_RIGHT;
            case MIDDLE -> ClickKind.MIDDLE;
            case NUMBER_KEY -> ClickKind.NUMBER_KEY;
            case DROP, CONTROL_DROP -> ClickKind.DROP;
            default -> ClickKind.OTHER;
        };
    }
}
