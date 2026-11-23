package com.ultikits.ultitools.ui;

import java.util.concurrent.CompletionStage;

/**
 * Where a container's contents are written. Module code.
 *
 * <p>During the owner's unload (a module reload or the server stopping) the framework calls {@code write} on the
 * unloading thread and then waits on that same thread for the answer, for at most the spec's commit timeout or
 * 10 seconds after the unload began, whichever comes first. Complete the stage without needing the server
 * thread: during an unload the server thread is the one waiting, and tasks scheduled for it no longer run. Keep
 * the resources {@code write} needs (connections, executors) open until the owner's containers have ended; the
 * framework ends them before the module's own shutdown code and before its data sources close.
 */
@FunctionalInterface
public interface ContainerStore {
    /**
     * Called once per commit on the container's thread, or on the unloading thread during the owner's unload. The
     * returned stage may complete on any thread and must complete with exactly one answer. An answer that does not
     * arrive within the spec's commit timeout, or within the unload's wait, counts as {@link StoreAnswer.Unknown}
     * and is ignored when it arrives. Once this is called the store holds {@code snapshot}: after an Unknown
     * answer, including one caused by a timeout or by the unload's wait, the store, not the framework, is
     * responsible for those items (for example a kept, retried write), even if it later decides not to store them.
     * Answer {@link StoreAnswer.Written} only if stored, and {@link StoreAnswer.Refused} only if the snapshot is not
     * stored and never will be.
     */
    CompletionStage<StoreAnswer> write(ContainerSnapshot snapshot);
}
