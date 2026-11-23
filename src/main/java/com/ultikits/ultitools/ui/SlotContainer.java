package com.ultikits.ultitools.ui;

import java.util.List;
import java.util.concurrent.CompletionStage;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Items that one or more framework screens show and edit. Owned by the module whose {@link UiService}
 * created it; outlives any screen. Not thread-safe: confined to its thread (on Paper, the main thread);
 * every method except {@link #owner()}, {@link #size()} and {@link #kind()} throws {@link IllegalStateException}
 * when called from another thread. {@link #owner()}, {@link #size()} and {@link #kind()} never change and may be
 * called from any thread, so a {@link Grid} that binds a container can be built on any thread.
 *
 * <p>Conservation: in a {@link ContainerSpec#storage storage} container, items enter only from a viewer's
 * cursor or inventory, from {@link #load} or from {@link ContainerSpec.Builder#initial}, and leave only to a
 * viewer's cursor or inventory, through {@link #drain()}, or through the disposition. A
 * {@link ContainerSpec#template template} container holds copies: no real item ever enters or leaves.
 *
 * <p>For a remote backpack, use a storage container with a baseline, a conditional store, COMMIT and an
 * undelivered handler. Bind visible indexes and page by changing bindings; a refused save returns put-ins,
 * never reclaims take-outs. A trade uses RETURN_TO_CONTRIBUTORS and endWhenUnviewed(false), binds each
 * trader's own offer EDITABLE and the other offer VIEW, freezes both after confirmation, and drains both
 * on completion. Cancellation calls end(). A kit editor uses template(size).baseline(kitItems) and reads
 * contents() to save definitions. Mail attachments use RETURN_TO_CONTRIBUTORS and drain() on confirmation.
 *
 * <p>Commit answers: Written moves the baseline and clears the ledger; Refused loads the store's current
 * contents and returns net put-ins; Unknown, an exceptional stage or timeout leaves the container FAILED
 * permanently, refunds nothing and transfers responsibility to the store. A concurrent commit returns the
 * in-flight stage, and unchanged contents do not call the store. Answers after the timeout are ignored and
 * logged. The result is completed on the container's thread.
 *
 * <p>End runs once, on explicit end(), the last completed viewer close when endWhenUnviewed is true, or
 * owner unload. RETURN_TO_CONTRIBUTORS returns remaining net put-ins in ledger order, reducing a taking
 * player's own contributions first, then other contributors, then the baseline. Unattributed baseline
 * items go to the handler under the nil UUID. COMMIT ends after the commit outcome; Unknown keeps items
 * with the store. drain() ends without a disposition. Templates discard their copies. After end the
 * container is not viewed and cannot be shown again. Showing or updating to a grid that binds an ended container
 * throws IllegalStateException when called on the container's thread; if the container ends after that call and
 * before the screen gets the snapshot, its bound slots show nothing and refuse every gesture.
 * In-memory contents are not crash-journalled; modules
 * needing crash safety must persist them.
 *
 * <p>Owner unload (a module reload or the server stopping) ends every container of the owner before the unload
 * returns, on the unloading thread. It first runs the owner's pending deliveries, then starts the write of every
 * COMMIT container whose contents differ from its baseline, and only then waits: each in-flight write is awaited
 * for at most its commit timeout or 10 seconds after the unload began, whichever comes first, and its real answer
 * is applied on the unloading thread (Written: committed; Refused: put-ins returned at once; otherwise the items
 * stay with the store). RETURN_TO_CONTRIBUTORS deliveries made during the unload run at once (see
 * {@link UiService#deliver}).
 *
 * <p>Known limitations. A transfer policy that calls this container's mutators is not supported (see
 * {@link TransferPolicy}). Amounts in {@code baseline}, {@code initial}, {@link #load} and a Refused answer's
 * contents are taken as given, even above an item's max stack size; they are the module's data. Under
 * RETURN_TO_CONTRIBUTORS with a baseline, a viewer who takes out a baseline item consumes other contributors'
 * credit before the baseline (ledger order), so that contributor's matching items may go to the undelivered
 * handler under the nil UUID instead of back to them. In-memory contents are not crash-journalled.
 */
@ApiStatus.NonExtendable
public interface SlotContainer {
    String owner();
    int size();
    ContainerKind kind();
    ContainerState state();
    /** A copy of the item at {@code index}; {@code ItemStack.empty()} if empty. */
    ItemStack get(int index);
    /** Copies of every index, length {@link #size()}. */
    List<ItemStack> contents();
    /** Whether a session shows this container: the snapshot its backend was last given binds it, and that session has not been closed yet. */
    boolean isViewed();
    /**
     * Puts the container into or out of {@link ContainerState#READ_ONLY} (for example while both traders have
     * confirmed). @throws IllegalStateException if {@code COMMITTING}, {@code FAILED} or {@code ENDED}
     */
    void setReadOnly(boolean readOnly);
    /**
     * Replaces the contents with items that come from the module (for example read from storage), and makes them
     * the new baseline; the ledger is cleared. Items previously held are discarded, so call this only when the
     * previous contents are stored elsewhere. @throws IllegalArgumentException if the list length is not
     * {@link #size()} @throws IllegalStateException unless {@code EDITABLE} or {@code READ_ONLY}
     */
    void load(List<ItemStack> contents);
    /** What changed since the baseline (the last load, initial contents or written commit). */
    ContainerChanges changes();
    /**
     * Writes the contents through the spec's {@link ContainerStore}: see the commit rules above.
     * Returns the in-flight stage if a commit is already running. Completes on this container's thread.
     * Moving an item from one index of the container to another counts as a take-out and a put-in, so it is written.
     * @throws IllegalStateException if the disposition is not COMMIT (no store), or the container is FAILED or ENDED
     */
    CompletionStage<CommitOutcome> commit();
    /**
     * Removes and returns every item; the container becomes {@code ENDED} and its disposition does not run.
     * The caller now owns the returned items (for example to hand a trade offer to the other player).
     * @throws IllegalStateException unless EDITABLE or READ_ONLY
     */
    List<ItemStack> drain();
    /** Runs the disposition now; idempotent; the stage completes once the disposition has finished. */
    CompletionStage<EndOutcome> end();
}
