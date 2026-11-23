package com.ultikits.ultitools.ui;

/** Constants may be added in a 7.x minor release. */
public enum ContainerState {
    /** Gestures on EDITABLE bindings are applied. */ EDITABLE,
    /** Set by the module; every gesture is refused. */ READ_ONLY,
    /** A commit is in flight; every gesture is refused. */ COMMITTING,
    /** A commit's outcome is unknown; read-only for good, nothing was refunded. */ FAILED,
    /** Ended (disposition ran, or drained); shows nothing; every method except getters throws. */ ENDED
}
