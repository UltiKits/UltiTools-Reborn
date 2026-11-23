package com.ultikits.ultitools.ui.chest;

import com.ultikits.ultitools.ui.Gesture;
import com.ultikits.ultitools.ui.GridSlot;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.event.inventory.DragType;
import org.jetbrains.annotations.ApiStatus;

/** Pure drag decoding, with raw slot numbers mapped to their ownership. */
@ApiStatus.Internal
public final class DragDecoder {
    private DragDecoder() { /* Static pure decoder. */ }

    /** Returns exactly one decision for a complete set of drag targets, including one-slot drags. */
    public static ClickDecision decode(DragType type, Map<Integer, SlotKind> targets) {
        if (targets.isEmpty()) { return new ClickDecision.Cancel(); }
        if (targets.values().stream().allMatch(kind -> kind == SlotKind.PLAYER_STORAGE)) {
            return new ClickDecision.Allow();
        }
        if (!targets.values().stream().allMatch(DragDecoder::bound)) { return new ClickDecision.Cancel(); }
        Set<GridSlot> slots = new LinkedHashSet<>();
        for (int raw : targets.keySet()) {
            if (raw < 0 || raw >= 54) { return new ClickDecision.Cancel(); }
            slots.add(GridSlot.ofIndex(raw));
        }
        return switch (type) {
            case EVEN -> new ClickDecision.Apply(new Gesture.Spread(slots, false));
            case SINGLE -> new ClickDecision.Apply(new Gesture.Spread(slots, true));
            default -> new ClickDecision.Cancel();
        };
    }

    private static boolean bound(SlotKind kind) {
        return kind == SlotKind.BOUND_EDITABLE || kind == SlotKind.BOUND_VIEW;
    }
}
