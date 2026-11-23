package com.ultikits.ultitools.ui.chest;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.ui.ClickKind;
import com.ultikits.ultitools.ui.Gesture;
import com.ultikits.ultitools.ui.GridSlot;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ClickDecoderExhaustiveTest {
    private static final GridSlot SLOT = GridSlot.ofIndex(4);
    private static final ClickDecision ALLOW = new ClickDecision.Allow();
    private static final ClickDecision CANCEL = new ClickDecision.Cancel();
    private static final EnumSet<InventoryAction> STORAGE_ALLOWED = EnumSet.of(
            InventoryAction.NOTHING, InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_SOME,
            InventoryAction.PICKUP_HALF, InventoryAction.PICKUP_ONE, InventoryAction.PLACE_ALL,
            InventoryAction.PLACE_SOME, InventoryAction.PLACE_ONE, InventoryAction.SWAP_WITH_CURSOR,
            InventoryAction.HOTBAR_SWAP, InventoryAction.DROP_ALL_SLOT, InventoryAction.DROP_ONE_SLOT);
    private static final EnumSet<InventoryAction> ORDINARY = EnumSet.of(
            InventoryAction.NOTHING, InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_SOME,
            InventoryAction.PICKUP_HALF, InventoryAction.PICKUP_ONE, InventoryAction.PLACE_ALL,
            InventoryAction.PLACE_SOME, InventoryAction.PLACE_ONE, InventoryAction.SWAP_WITH_CURSOR,
            InventoryAction.DROP_ALL_CURSOR, InventoryAction.DROP_ONE_CURSOR, InventoryAction.DROP_ALL_SLOT,
            InventoryAction.DROP_ONE_SLOT, InventoryAction.MOVE_TO_OTHER_INVENTORY,
            InventoryAction.HOTBAR_MOVE_AND_READD, InventoryAction.HOTBAR_SWAP,
            InventoryAction.CLONE_STACK, InventoryAction.COLLECT_TO_CURSOR, InventoryAction.UNKNOWN);
    private static final Map<ClickType, ClickKind> ICON_KINDS = iconKinds();

    @Test void paperConstantsRequireReviewWhenTheyChange() {
        assertThat(InventoryAction.values()).hasSize(25);
        assertThat(ClickType.values()).hasSize(14);
        assertThat(InventoryCloseEvent.Reason.values()).hasSize(9);
        assertThat(SlotKind.values()).hasSize(6);
        assertThat(CursorState.values()).hasSize(3);
    }

    @Test void everyCombinationMatchesTheIndependentlyWrittenTable() {
        List<String> mismatches = new ArrayList<>();
        int combinations = 0;
        int mismatchCount = 0;
        for (ClickInput input : allInputs()) {
            combinations++;
            ClickDecision actual = ClickDecoder.decode(input);
            ClickDecision expected = expected(input);
            if (!expected.equals(actual)) {
                mismatchCount++;
                if (mismatches.size() < 20) {
                    mismatches.add(input + " expected=" + expected + " actual=" + actual);
                }
            }
        }
        System.out.printf("CLICK combinations=%d mismatches=%d%n", combinations, mismatchCount);
        assertThat(combinations).isEqualTo(25 * 14 * 6 * 3 * 2);
        assertThat(mismatchCount).withFailMessage("%s", mismatches).isZero();
    }

    @Test void allHotbarIndexesArePreservedAndInvalidOnesCancel() {
        for (int hotbar = -1; hotbar <= 9; hotbar++) {
            ClickInput input = new ClickInput(InventoryAction.HOTBAR_SWAP, ClickType.NUMBER_KEY,
                    SlotKind.BOUND_EDITABLE, CursorState.EMPTY, true, SLOT, 17, hotbar);
            ClickDecision expected = hotbar >= 0 && hotbar <= 8
                    ? new ClickDecision.Apply(new Gesture.HotbarSwap(SLOT, hotbar)) : CANCEL;
            assertThat(ClickDecoder.decode(input)).isEqualTo(expected);
        }
    }

    private static List<ClickInput> allInputs() {
        List<ClickInput> inputs = new ArrayList<>();
        for (InventoryAction action : InventoryAction.values()) {
            for (ClickType click : ClickType.values()) {
                for (SlotKind kind : SlotKind.values()) {
                    for (CursorState cursor : CursorState.values()) {
                        inputs.add(new ClickInput(action, click, kind, cursor, false, SLOT, 17, 2));
                        inputs.add(new ClickInput(action, click, kind, cursor, true, SLOT, 17, 2));
                    }
                }
            }
        }
        return inputs;
    }

    private static ClickDecision expected(ClickInput input) {
        return switch (input.kind()) {
            case ICON -> new ClickDecision.Report(SLOT, ICON_KINDS.get(input.click()));
            case EMPTY -> CANCEL;
            case PLAYER_STORAGE -> storage(input.action());
            case OUTSIDE -> switch (input.action()) {
                case NOTHING, DROP_ALL_CURSOR, DROP_ONE_CURSOR -> ALLOW;
                default -> CANCEL;
            };
            case BOUND_EDITABLE, BOUND_VIEW -> bound(input);
        };
    }

    private static ClickDecision storage(InventoryAction action) {
        if (STORAGE_ALLOWED.contains(action)) { return ALLOW; }
        return action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                ? new ClickDecision.Apply(new Gesture.QuickMoveIn(17)) : CANCEL;
    }

    private static ClickDecision bound(ClickInput input) {
        if (!ORDINARY.contains(input.action())) { return CANCEL; }
        return switch (input.click()) {
            case NUMBER_KEY -> input.action() == InventoryAction.HOTBAR_SWAP
                    || input.action() == InventoryAction.HOTBAR_MOVE_AND_READD
                    ? new ClickDecision.Apply(new Gesture.HotbarSwap(SLOT, 2)) : CANCEL;
            case SHIFT_LEFT, SHIFT_RIGHT -> input.action() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                    ? new ClickDecision.Apply(new Gesture.QuickMoveOut(SLOT)) : CANCEL;
            case LEFT -> leftTable(input);
            case RIGHT -> rightTable(input);
            default -> CANCEL;
        };
    }

    // Each row is [cursor empty, cursor similar, cursor different]; the two rows distinguish slot contents.
    private static ClickDecision leftTable(ClickInput input) {
        List<ClickDecision> full = List.of(new ClickDecision.Apply(new Gesture.Pickup(SLOT, false)),
                new ClickDecision.Apply(new Gesture.Place(SLOT, false)),
                new ClickDecision.Apply(new Gesture.SwapWithCursor(SLOT)));
        List<ClickDecision> empty = List.of(new ClickDecision.Apply(new Gesture.Pickup(SLOT, false)),
                new ClickDecision.Apply(new Gesture.Place(SLOT, false)),
                new ClickDecision.Apply(new Gesture.Place(SLOT, false)));
        return (input.slotEmpty() ? empty : full).get(input.cursor().ordinal());
    }

    private static ClickDecision rightTable(ClickInput input) {
        List<ClickDecision> full = List.of(new ClickDecision.Apply(new Gesture.Pickup(SLOT, true)),
                new ClickDecision.Apply(new Gesture.Place(SLOT, true)), CANCEL);
        List<ClickDecision> empty = List.of(new ClickDecision.Apply(new Gesture.Pickup(SLOT, true)),
                new ClickDecision.Apply(new Gesture.Place(SLOT, true)),
                new ClickDecision.Apply(new Gesture.Place(SLOT, true)));
        return (input.slotEmpty() ? empty : full).get(input.cursor().ordinal());
    }

    private static Map<ClickType, ClickKind> iconKinds() {
        Map<ClickType, ClickKind> kinds = new EnumMap<>(ClickType.class);
        kinds.put(ClickType.LEFT, ClickKind.LEFT);
        kinds.put(ClickType.RIGHT, ClickKind.RIGHT);
        kinds.put(ClickType.SHIFT_LEFT, ClickKind.SHIFT_LEFT);
        kinds.put(ClickType.SHIFT_RIGHT, ClickKind.SHIFT_RIGHT);
        kinds.put(ClickType.MIDDLE, ClickKind.MIDDLE);
        kinds.put(ClickType.NUMBER_KEY, ClickKind.NUMBER_KEY);
        kinds.put(ClickType.DROP, ClickKind.DROP);
        kinds.put(ClickType.CONTROL_DROP, ClickKind.DROP);
        kinds.put(ClickType.DOUBLE_CLICK, ClickKind.OTHER);
        kinds.put(ClickType.CREATIVE, ClickKind.OTHER);
        kinds.put(ClickType.SWAP_OFFHAND, ClickKind.OTHER);
        kinds.put(ClickType.WINDOW_BORDER_LEFT, ClickKind.OTHER);
        kinds.put(ClickType.WINDOW_BORDER_RIGHT, ClickKind.OTHER);
        kinds.put(ClickType.UNKNOWN, ClickKind.OTHER);
        return kinds;
    }
}
