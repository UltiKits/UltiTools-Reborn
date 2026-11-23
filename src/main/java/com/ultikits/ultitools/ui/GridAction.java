package com.ultikits.ultitools.ui;

/** Click handler. Runs on the viewer's entity thread. Exceptions are logged with the owner and swallowed. */
@FunctionalInterface
public interface GridAction {
    /** Handles one click. */
    void onClick(GridClick click);
}
