package com.ultikits.ultitools.ui;

import java.util.concurrent.CompletionStage;

/** Where a container's contents are written. Module code. */
@FunctionalInterface
public interface ContainerStore {
    /**
     * Called once per commit on the container's thread. The returned stage may complete on any thread and
     * must complete with exactly one answer; a late or missing answer counts as {@link StoreAnswer.Unknown}
     * after the spec's commit timeout. Once it is called the store holds {@code snapshot}: after an Unknown
     * answer the store, not the framework, is responsible for those items (for example a kept, retried write).
     */
    CompletionStage<StoreAnswer> write(ContainerSnapshot snapshot);
}
