package com.ultikits.ultitools.ui.runtime;

import com.ultikits.ultitools.ui.ContainerSpec;
import com.ultikits.ultitools.ui.GestureEngine;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.UiSession;
import org.jetbrains.annotations.ApiStatus;

/** Placeholder until the editable-slot runtime is installed. No container can be created here. */
@ApiStatus.Internal
public final class NoContainerRuntime implements ContainerRuntime {
    @Override public SlotContainer create(UiOwner owner, ContainerSpec spec, ContainerHost host) {
        throw new IllegalStateException("No container runtime installed");
    }
    @Override public void attach(SlotContainer container, UiSession<?> session) {
        // No containers exist in this runtime.
    }
    @Override public void detach(SlotContainer container, UiSession<?> session) {
        // No containers exist in this runtime.
    }
    @Override public GestureEngine engine() { return (grid, viewer, side, gesture) -> false; }
    @Override public void prepareUnload(UiOwner owner, long deadlineNanos) {
        // No containers exist in this runtime.
    }
    @Override public void startUnloadCommits(UiOwner owner) {
        // No stores exist in this runtime.
    }
    @Override public void finishUnload(UiOwner owner, long deadlineNanos) {
        // No resources exist in this runtime.
    }
}
