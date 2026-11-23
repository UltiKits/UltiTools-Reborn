package com.ultikits.ultitools.ui.runtime;

import com.ultikits.ultitools.ui.Ack;
import com.ultikits.ultitools.ui.CloseReason;
import com.ultikits.ultitools.ui.ContainerSpec;
import com.ultikits.ultitools.ui.Delivery;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.ui.ShowOptions;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.UiIntent;
import com.ultikits.ultitools.ui.UiResult;
import com.ultikits.ultitools.ui.UiService;
import com.ultikits.ultitools.ui.UiSession;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** One module's view of the shared session registry. */
@ApiStatus.Internal
final class RuntimeUiService implements UiService {
    final UiRuntime runtime;
    private final String owner;
    boolean unloaded;
    boolean unloading;
    @Nullable Thread unloadingThread;

    RuntimeUiService(UiRuntime runtime, String owner, boolean unloaded) {
        this.runtime = runtime;
        this.owner = owner;
        this.unloaded = unloaded;
    }
    @Override public String owner() { return owner; }
    @Override public <R> UiSession<R> show(Player viewer, UiIntent<R> intent) {
        return show(viewer, intent, ShowOptions.defaults());
    }
    @Override public GridSession show(Player viewer, Grid grid) { return show(viewer, grid, ShowOptions.defaults()); }
    @Override public GridSession show(Player viewer, Grid grid, ShowOptions options) {
        return (GridSession) show(viewer, (UiIntent<Ack>) Objects.requireNonNull(grid, "grid"), options);
    }
    @Override public <R> UiSession<R> show(Player viewer, UiIntent<R> intent, ShowOptions options) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(options, "options");
        RuntimeSession<R> session = newSession(viewer, intent);
        if (runtime.add(session)) {
            session.choose(new UiResult.Closed<>(CloseReason.OWNER_UNLOADED));
            session.closeAndComplete();
        } else if (!viewer.isOnline()) {
            session.requestClose(CloseReason.DISCONNECTED);
        } else {
            runtime.dispatch(viewer, () -> session.openTask(options));
        }
        return session;
    }
    private <R> RuntimeSession<R> newSession(Player viewer, UiIntent<R> intent) {
        if (intent instanceof Grid) { throw new UnsupportedOperationException("Grid sessions not implemented"); }
        return new RuntimeSession<>(this, viewer, intent);
    }
    @Override public SlotContainer container(ContainerSpec spec) {
        throw new UnsupportedOperationException("Container creation not implemented");
    }
    @Override public CompletionStage<Delivery> deliver(Player player, List<ItemStack> items) {
        throw new UnsupportedOperationException("Item delivery not implemented");
    }
}
