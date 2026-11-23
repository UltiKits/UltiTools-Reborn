package com.ultikits.ultitools.ui.runtime;

import com.ultikits.ultitools.ui.ContainerSpec;
import com.ultikits.ultitools.ui.GestureEngine;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.UiSession;
import org.jetbrains.annotations.ApiStatus;

/** The editable-slot half of the UI runtime. Internal; implemented by the chest backend item. */
@ApiStatus.Internal
public interface ContainerRuntime {
    /** Creates a container owned by {@code owner}. Called on the server thread, after the service's own checks. */
    SlotContainer create(String owner, ContainerSpec spec, ContainerHost host);
    /** {@code session} now shows {@code container} (its rendered snapshot binds it). Server thread. */
    void attach(SlotContainer container, UiSession<?> session);
    /**
     * {@code session} no longer shows {@code container}. If the container's owner is unloading, the container ends
     * through the unload path, never through endWhenUnviewed. Server thread.
     */
    void detach(SlotContainer container, UiSession<?> session);
    /** The engine that applies gestures to this runtime's containers. */
    GestureEngine engine();
    /** Unload step 2: freeze new work for the owner's containers. Unloading thread. */
    void prepareUnload(String owner);
    /** Unload step 4: start the write of every COMMIT container of the owner that has changes. Unloading thread. */
    void startUnloadCommits(String owner);
    /** Unload step 6: await answers until {@code deadlineNanos} (System.nanoTime) and end every container of the owner. */
    void finishUnload(String owner, long deadlineNanos);
}
