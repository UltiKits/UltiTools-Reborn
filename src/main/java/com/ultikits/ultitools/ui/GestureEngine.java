package com.ultikits.ultitools.ui;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/**
 * Applies a gesture of {@code viewer} on {@code grid} atomically. Implemented by the framework's container.
 * Storage transfers conserve each similar item's total over container, cursor and viewer inventory;
 * templates never change the viewer's real items. Unsupported gestures move nothing. A VIEW binding or
 * non-EDITABLE container refuses before consulting policy. A swap requires both transfers allowed in full;
 * other transfers may be reduced by policy. Policy is called once per proposed transfer, before changes,
 * on the container thread, with copies; exceptions refuse and are logged with the owner, and refusal text
 * is rendered once for the viewer. Each applied transfer updates the ledger, then calls onChange once;
 * listener exceptions are logged and swallowed. Every viewing session is redrawn before the next tick ends.
 * The backend refuses input for sessions whose outcome has already been chosen.
 */
@ApiStatus.Internal
public interface GestureEngine {
    /**
     * @param grid the snapshot the backend was last given (by open or update), not {@code GridSession#current()}
     * @return whether anything moved
     */
    boolean apply(Grid grid, Player viewer, ViewerItems side, Gesture gesture);
}
