package com.ultikits.ultitools.ui;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/**
 * Shows intents. The instance a module receives by injection is owned by that module. May be called from
 * any thread.
 *
 * <p>A player has at most one open session. When an intent opens for a player who has one, the previous
 * session ends with {@link CloseReason#REPLACED} and its screen is closed before the new one opens. An intent
 * that no backend supports ends with {@link CloseReason#UNSUPPORTED}, opens nothing and leaves the previous
 * session open.
 *
 * <p>{@code show} to a viewer who is offline returns a session that is already not open, with
 * {@code Closed(DISCONNECTED)} chosen; its result completes in a task of the global context. A viewer who
 * quits ends the session with {@link CloseReason#DISCONNECTED}.
 *
 * <p>Unloading the owning module ends every session of that owner that has not completed: before the unload
 * returns, on the unloading thread, each screen is closed and each result completed, with
 * {@code Closed(OWNER_UNLOADED)} or with the outcome already chosen if there was one. Stopping the server
 * unloads every module, so no session is left incomplete. {@code show} after the unload returns a session
 * whose result is already {@code Closed(OWNER_UNLOADED)} and opens nothing.
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
