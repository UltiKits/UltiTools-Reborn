package com.ultikits.ultitools.ui;

/** Why an intent ended without an answer. Constants may be added in a 7.x minor release. */
public enum CloseReason {
    /** The player explicitly closed a chest or chose a Dialog's cancel/exit action. */ PLAYER,
    /**
     * A chest was closed by the game or another plugin, or its first open/title reopen failed.
     * For Dialogs: death, world change, or an uncancelled inventory opening interrupted the screen.
     */ INTERRUPTED,
    /** The player left the server; this includes a chest CANT_USE followed by a quit in the same tick. */ DISCONNECTED,
    /** Another intent was shown to the same player. */ REPLACED,
    /** {@link ShowOptions#timeout()} elapsed. */ TIMED_OUT,
    /** {@link UiSession#close()} was called. */ CLOSED_BY_CODE,
    /** The module that showed it was unloaded. */ OWNER_UNLOADED,
    /** No backend can show this intent to this player. */ UNSUPPORTED
}
