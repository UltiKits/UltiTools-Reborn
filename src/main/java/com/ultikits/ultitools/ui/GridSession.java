package com.ultikits.ultitools.ui;

import org.jetbrains.annotations.ApiStatus;

/** A shown {@link Grid}. */
@ApiStatus.NonExtendable
public interface GridSession extends UiSession<Ack> {
    /** Replaces the snapshot; the backend redraws changed slots. Same row count required. No-op after close. */
    void update(Grid next);
    /** The snapshot currently shown. */
    Grid current();
}
