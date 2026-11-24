package com.ultikits.ultitools.ui.runtime;

import com.ultikits.ultitools.ui.ContainerSpec;
import com.ultikits.ultitools.ui.GestureEngine;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.UiSession;
import org.jetbrains.annotations.ApiStatus;

/**
 * The editable-slot half of the UI runtime. Internal; implemented by the chest backend item.
 * Ownership is keyed by the opaque handle, never by its diagnostic name or SlotContainer#owner().
 */
@ApiStatus.Internal
public interface ContainerRuntime {
    /** Creates a container for this module copy and its host. Server thread, after the service's own checks. */
    SlotContainer create(UiOwner owner, ContainerSpec spec, ContainerHost host);
    /** {@code session} now shows {@code container}. Idempotent for a session/container pair. Server thread. */
    void attach(SlotContainer container, UiSession<?> session);
    /**
     * {@code session} no longer shows {@code container}. Must tolerate an absent or partially failed attach.
     * If the container's owner is unloading, end through the unload path using the deadline supplied to
     * {@link #prepareUnload}, never through endWhenUnviewed. Server thread.
     */
    void detach(SlotContainer container, UiSession<?> session);
    /** The engine that applies gestures to this runtime's containers. */
    GestureEngine engine();
    /**
     * Unload step 2: freeze new work for this module copy and retain the shared absolute System.nanoTime deadline.
     * All later detach/wait operations use that deadline, without restarting a timeout. Unloading thread.
     */
    void prepareUnload(UiOwner owner, long deadlineNanos);
    /** Unload step 4: start changed COMMIT writes for this copy, reusing any write already in flight. Unloading thread. */
    void startUnloadCommits(UiOwner owner);
    /** Unload step 6: await answers until the same deadline and end every container of this copy. Unloading thread. */
    void finishUnload(UiOwner owner, long deadlineNanos);
}
