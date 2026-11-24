package com.ultikits.ultitools.ui.runtime;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Best-effort lifecycle cleanup: isolate each external call and rethrow a VM failure only after all steps ran. */
@ApiStatus.Internal
final class RuntimeCleanup {
    private final UiRuntime runtime;
    private final String owner;
    private @Nullable VirtualMachineError fatal;

    RuntimeCleanup(UiRuntime runtime, String owner) {
        this.runtime = runtime;
        this.owner = owner;
    }
    void run(String step, Runnable action) {
        try { action.run(); } catch (Throwable failure) {
            runtime.warn(owner, step, failure);
            if (failure instanceof VirtualMachineError vm && fatal == null) { fatal = vm; }
        }
    }
    void finish() {
        if (fatal != null) { throw fatal; }
    }
}
