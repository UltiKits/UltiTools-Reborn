package com.ultikits.ultitools.ui.chest;

import org.jetbrains.annotations.ApiStatus;

/** The ownership of a slot in the last rendered grid. */
@ApiStatus.Internal
public enum SlotKind {
    ICON, EMPTY, BOUND_EDITABLE, BOUND_VIEW, PLAYER_STORAGE, OUTSIDE
}
