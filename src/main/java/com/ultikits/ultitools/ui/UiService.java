package com.ultikits.ultitools.ui;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/**
 * Shows intents. The instance a module receives by injection is owned by that module: unloading the
 * module closes its open sessions with {@link CloseReason#OWNER_UNLOADED}. May be called from any thread.
 * A player has at most one open session; showing another closes the previous one with
 * {@link CloseReason#REPLACED}.
 */
@ApiStatus.NonExtendable
public interface UiService {
    /** Owning module's name, for diagnostics. */
    String owner();
    /** Shows an intent with intent-dependent defaults. */
    <R> UiSession<R> show(Player viewer, UiIntent<R> intent);
    /** Shows an intent with explicit options. */
    <R> UiSession<R> show(Player viewer, UiIntent<R> intent, ShowOptions options);
    /** Shows a grid with intent-dependent defaults. */
    GridSession show(Player viewer, Grid grid);
    /** Shows a grid with explicit options. */
    GridSession show(Player viewer, Grid grid, ShowOptions options);
}
