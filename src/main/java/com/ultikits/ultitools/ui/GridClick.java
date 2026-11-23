package com.ultikits.ultitools.ui;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/** One click on a grid slot. */
@ApiStatus.NonExtendable
public interface GridClick {
    /** The player who clicked. */
    Player viewer();
    /** The clicked slot. */
    GridSlot slot();
    /** The reported kind of click. */
    ClickKind kind();
    /** The session the click belongs to, to update or close the screen. */
    GridSession session();
}
