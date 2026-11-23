package com.ultikits.ultitools.ui;

/**
 * Click handler. Runs in a later task of the viewer's entity context, never inside the backend's own event
 * handler, so it may call {@link GridSession#update(Grid)}, {@link UiSession#close()} or show another intent.
 * The action is taken from the snapshot the backend had last been given when the click was reported, and runs
 * only if the session is still open when its task runs. Exceptions are logged with the owner and swallowed.
 * A click the player made on an older picture that reaches the server after a redraw runs the action of the
 * redrawn slot; keep actions safe to run on whatever the slot shows now.
 */
@FunctionalInterface
public interface GridAction {
    /** Handles one click. */
    void onClick(GridClick click);
}
