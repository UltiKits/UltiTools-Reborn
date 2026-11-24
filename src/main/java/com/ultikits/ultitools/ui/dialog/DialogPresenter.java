package com.ultikits.ultitools.ui.dialog;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/** Boundary between the backend's lifecycle and Paper's server-owned registry. */
@ApiStatus.Internal
public interface DialogPresenter {
    /** Shows the model, retaining only the invalidatable ticket in button callbacks. */
    void show(Player viewer, DialogModel model, DialogTicket ticket);
    /** Dismisses the viewer's currently showing dialog. */
    void dismiss(Player viewer);
}
