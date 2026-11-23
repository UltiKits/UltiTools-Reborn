package com.ultikits.ultitools.ui;

/** Why an intent ended without an answer. Constants may be added in a 7.x minor release. */
public enum CloseReason {
    /** The player closed or dismissed it. */ PLAYER,
    /** The player left the server. */ DISCONNECTED,
    /** Another intent was shown to the same player. */ REPLACED,
    /** {@link ShowOptions#timeout()} elapsed. */ TIMED_OUT,
    /** {@link UiSession#close()} was called. */ CLOSED_BY_CODE,
    /** The module that showed it was unloaded. */ OWNER_UNLOADED,
    /** No backend can show this intent to this player. */ UNSUPPORTED
}
