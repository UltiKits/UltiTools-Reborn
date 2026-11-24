package com.ultikits.ultitools.ui.chest;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.ui.Gesture;
import com.ultikits.ultitools.ui.GridSlot;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.event.inventory.DragType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class DragDecoderExhaustiveTest {
    private static final Set<SlotKind> BOUND = EnumSet.of(SlotKind.BOUND_EDITABLE, SlotKind.BOUND_VIEW);

    @Test void everyNonemptyTargetCombinationAndDragTypeMatchesTheTable() {
        int combinations = 0;
        int mismatches = 0;
        for (DragType type : DragType.values()) {
            for (Map<Integer, SlotKind> targets : targets()) {
                combinations++;
                if (!expected(type, targets).equals(DragDecoder.decode(type, targets))) { mismatches++; }
            }
        }
        assertThat(DragType.values()).hasSize(2);
        assertThat(combinations).isEqualTo(2 * (6 + 36 + 216));
        assertThat(mismatches).isZero();
    }

    @Test void noTargetsCancelRatherThanVacuouslyAllowing() {
        for (DragType type : DragType.values()) {
            assertThat(DragDecoder.decode(type, Map.of())).isEqualTo(new ClickDecision.Cancel());
        }
    }

    private static List<Map<Integer, SlotKind>> targets() {
        List<Map<Integer, SlotKind>> targets = new ArrayList<>();
        for (SlotKind first : SlotKind.values()) {
            targets.add(Map.of(0, first));
            for (SlotKind second : SlotKind.values()) {
                targets.add(Map.of(0, first, 1, second));
                for (SlotKind third : SlotKind.values()) {
                    Map<Integer, SlotKind> row = new LinkedHashMap<>();
                    row.put(0, first);
                    row.put(1, second);
                    row.put(2, third);
                    targets.add(row);
                }
            }
        }
        return targets;
    }

    private static ClickDecision expected(DragType type, Map<Integer, SlotKind> targets) {
        Set<SlotKind> areas = EnumSet.copyOf(targets.values());
        if (areas.equals(Set.of(SlotKind.PLAYER_STORAGE))) { return new ClickDecision.Allow(); }
        if (!BOUND.containsAll(areas)) { return new ClickDecision.Cancel(); }
        Set<GridSlot> slots = switch (targets.size()) {
            case 1 -> Set.of(GridSlot.ofIndex(0));
            case 2 -> Set.of(GridSlot.ofIndex(0), GridSlot.ofIndex(1));
            case 3 -> Set.of(GridSlot.ofIndex(0), GridSlot.ofIndex(1), GridSlot.ofIndex(2));
            default -> throw new AssertionError("Unexpected table size");
        };
        return new ClickDecision.Apply(new Gesture.Spread(slots, type == DragType.SINGLE));
    }
}
