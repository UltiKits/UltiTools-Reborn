package com.ultikits.ultitools.ui.chest;

import com.ultikits.ultitools.ui.ClickKind;
import com.ultikits.ultitools.ui.Gesture;
import com.ultikits.ultitools.ui.GridSlot;
import java.util.Objects;
import org.jetbrains.annotations.ApiStatus;

/** A pure decision: only Allow may reach vanilla inventory handling. */
@ApiStatus.Internal
public sealed interface ClickDecision {
    /** Leaves vanilla handling and any previous cancellation untouched. */
    record Allow() implements ClickDecision { }
    /** Cancels without moving anything. */
    record Cancel() implements ClickDecision { }
    /** Cancels and asks the runtime's gesture entry point to move real items. */
    record Apply(Gesture gesture) implements ClickDecision {
        public Apply { Objects.requireNonNull(gesture, "gesture"); }
    }
    /** Cancels and records an icon click; never runs module code here. */
    record Report(GridSlot slot, ClickKind kind) implements ClickDecision {
        public Report {
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(kind, "kind");
        }
    }
}
