package com.ultikits.ultitools.ui;

/** Constants may be added in a 7.x minor release. */
public enum ContainerState {
    /** Gestures on EDITABLE bindings are applied. */ EDITABLE,
    /** Set by the module; every gesture is refused; the module may still load, commit, drain or end it. */ READ_ONLY,
    /** A commit is in flight; every gesture is refused; setReadOnly, load and drain throw; commit returns the in-flight stage; end waits for the answer. */ COMMITTING,
    /** A commit's outcome is unknown; nothing was refunded; every gesture is refused; setReadOnly, load, commit and drain throw; end ends it with UNKNOWN_KEPT_BY_STORE. */ FAILED,
    /** Ended (disposition ran, or drained); shows nothing and is not viewed. The getters, changes() (empty) and end() (the same stage) still answer; setReadOnly, load, commit and drain throw IllegalStateException. */ ENDED
}
