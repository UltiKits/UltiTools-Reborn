package com.ultikits.ultitools.ui;

import org.jetbrains.annotations.ApiStatus;

/** A shown {@link Grid}. */
@ApiStatus.NonExtendable
public interface GridSession extends UiSession<Ack> {
    /**
     * Replaces the snapshot: {@link #current()} returns {@code next} as soon as this returns. The backend is
     * told in a later task of the viewer's entity context, never inside this call; several updates made before
     * that task may reach the backend as one, the latest. A click runs the action of the snapshot the backend
     * had last been given when the click was reported (see {@link GridAction}). The title may change; a backend
     * may reopen the screen to show it, which does not end the session. Does nothing once the session is not
     * open ({@link #isOpen()}).
     * @throws IllegalArgumentException if the session is open and {@code next} has another row count
     */
    void update(Grid next);
    /** The snapshot currently shown. */
    Grid current();
}
