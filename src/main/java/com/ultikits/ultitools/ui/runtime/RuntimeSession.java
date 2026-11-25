package com.ultikits.ultitools.ui.runtime;

import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.ui.ClickKind;
import com.ultikits.ultitools.ui.CloseReason;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSlot;
import com.ultikits.ultitools.ui.ShowOptions;
import com.ultikits.ultitools.ui.UiBackend;
import com.ultikits.ultitools.ui.UiIntent;
import com.ultikits.ultitools.ui.UiResult;
import com.ultikits.ultitools.ui.UiSession;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Outcome selection is synchronous; screen cleanup and result delivery are ordered continuations.
 * Existing screens close on the viewer lane (or the global pump after retirement), except owner unload,
 * which closes them synchronously on the unloading thread. A show rejected during or after unload has
 * neither a backend nor bindings and completes inline on its caller's thread, including an async caller.
 * Future dependents run without the runtime lock.
 * @param <R> the intent's answer type
 */
@ApiStatus.Internal
class RuntimeSession<R> implements UiSession<R> {
    final RuntimeUiService service;
    final UiRuntime runtime;
    final Player viewer;
    final UUID viewerId;
    private final ViewerLane lane;
    private final UiIntent<R> intent;
    private final CompletableFuture<UiResult<R>> completion = new CompletableFuture<>();
    @Nullable UiBackend backend;
    private @Nullable TaskHandle timeout;
    private @Nullable UiResult<R> chosen;
    private boolean finished;

    RuntimeSession(RuntimeUiService service, Player viewer, UiIntent<R> intent) {
        this.service = service;
        runtime = service.runtime;
        this.viewer = viewer;
        viewerId = viewer.getUniqueId();
        lane = runtime.lane(viewer);
        this.intent = intent;
    }
    @Override public Player viewer() { return viewer; }
    @Override public UiIntent<R> intent() { return intent; }
    @Override public CompletionStage<UiResult<R>> result() { return completion.minimalCompletionStage(); }
    @Override public boolean isOpen() { synchronized (runtime.lock) { return isOpenLocked(); } }
    boolean isOpenLocked() { return chosen == null; }
    @Override public void close() { requestClose(CloseReason.CLOSED_BY_CODE); }

    final void requestClose(CloseReason reason) { requestChoose(new UiResult.Closed<>(Objects.requireNonNull(reason, "reason"))); }
    final void dispatch(Runnable task) { lane.execute(task); }
    final void requestChoose(UiResult<R> result) {
        if (choose(result)) { dispatch(this::closeAndComplete); }
    }
    final boolean choose(UiResult<R> result) {
        TaskHandle cancel;
        synchronized (runtime.lock) {
            if (chosen != null) { return false; }
            chosen = result;
            cancel = timeout;
            timeout = null;
        }
        if (cancel != null) { cancelTimeout(cancel); }
        return true;
    }

    final void closeAndComplete() {
        UiResult<R> result;
        UiBackend closing;
        synchronized (runtime.lock) {
            if (finished || chosen == null) { return; }
            finished = true;
            runtime.removeLocked(this);
            result = chosen;
            closing = backend;
        }
        try {
            if (closing != null) {
                try { closing.close(this); } catch (Throwable failure) {
                    runtime.warn(service.owner(), "Backend close failed", failure);
                    UiRuntime.rethrowFatal(failure);
                }
            }
        } finally {
            try { detachBindings(); } catch (Throwable failure) {
                runtime.warn(service.owner(), "Container detach failed", failure);
                UiRuntime.rethrowFatal(failure);
            } finally {
                completion.complete(result);
            }
        }
    }

    /**
     * No supporting backend leaves the previous session untouched. Once a supporting backend is selected,
     * replacement is committed before calling open: an open failure leaves the old session REPLACED and
     * never restores its screen. Backends may select an outcome first (chests use INTERRUPTED for rejected
     * opens); an otherwise unhandled open exception selects UNSUPPORTED.
     */
    final void openTask(ShowOptions options) {
        if (!isOpen()) { return; }
        try {
            if (!viewer.isOnline()) {
                if (intent instanceof Grid) {
                    takeBindings();
                    requestClose(CloseReason.INTERRUPTED);
                } else {
                    requestClose(CloseReason.DISCONNECTED);
                }
                return;
            }
            UiBackend selected = runtime.select(this);
            if (!isOpen()) { return; }
            if (selected == null) { requestClose(CloseReason.UNSUPPORTED); return; }
            takeBindings();
            runtime.replacePrevious(this);
            if (!runtime.activate(this, selected)) { return; }
            selected.open(this, callbacks());
            if (this instanceof RuntimeGridSession grid) { grid.renderPicture(); }
        } catch (Throwable failure) {
            runtime.warn(service.owner(), "UI open failed", failure);
            requestClose(CloseReason.UNSUPPORTED);
            UiRuntime.rethrowFatal(failure);
            return;
        }
        try { scheduleTimeout(options); } catch (Throwable failure) {
            runtime.warn(service.owner(), "UI timeout scheduling failed", failure);
            requestClose(CloseReason.UNSUPPORTED);
            UiRuntime.rethrowFatal(failure);
        }
    }

    void takeBindings() { /* Non-grid intents have no bindings. */ }
    void detachBindings() { /* Non-grid intents have no bindings. */ }
    void clicked(GridSlot slot, ClickKind kind) { /* Non-grid intents have no grid actions. */ }

    private void scheduleTimeout(ShowOptions options) {
        Optional<Duration> delay = intent instanceof Grid ? options.timeout()
                : Optional.of(options.timeout().orElse(Duration.ofMinutes(10)));
        if (!isOpen() || delay.isEmpty()) { return; }
        TaskHandle handle = runtime.scheduler.global().runLater(delay.orElseThrow(), () -> requestClose(CloseReason.TIMED_OUT));
        boolean cancel;
        synchronized (runtime.lock) {
            cancel = !isOpenLocked();
            if (!cancel) { timeout = handle; }
        }
        if (cancel) { cancelTimeout(handle); }
        handle.completion().whenComplete((ignored, failure) -> {
            if (failure != null && isOpen()) {
                Throwable cause = failure instanceof CompletionException wrapped
                        && wrapped.getCause() != null ? wrapped.getCause() : failure;
                runtime.warn(service.owner(), "UI timeout task failed", cause);
                requestClose(CloseReason.UNSUPPORTED);
            }
        });
    }

    private void cancelTimeout(TaskHandle handle) {
        try { handle.cancel(); } catch (Throwable failure) {
            // Cancellation is best-effort; the chosen outcome must still be delivered.
            runtime.warn(service.owner(), "UI timeout cancellation failed", failure);
        }
    }

    private UiBackend.BackendCallbacks<R> callbacks() {
        return new UiBackend.BackendCallbacks<>() {
            @Override public void completed(R value) {
                Objects.requireNonNull(value, "value");
                if (!(intent instanceof Grid)) { requestChoose(new UiResult.Completed<>(value)); }
            }
            @Override public void closed(CloseReason reason) { requestClose(reason); }
            @Override public void clicked(GridSlot slot, ClickKind kind) {
                RuntimeSession.this.clicked(Objects.requireNonNull(slot, "slot"), Objects.requireNonNull(kind, "kind"));
            }
        };
    }
}
