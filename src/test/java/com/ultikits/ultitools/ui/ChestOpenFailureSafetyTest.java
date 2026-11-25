package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.chest.ChestListener;
import com.ultikits.ultitools.ui.chest.PlayerViewerItems;
import com.ultikits.ultitools.ui.container.FrameworkContainerRuntime;
import com.ultikits.ultitools.ui.runtime.ContainerRuntime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockbukkit.mockbukkit.MockBukkit;

class ChestOpenFailureSafetyTest extends ChestTestFixture {
    private ContainerRuntime containers;

    @Override ContainerRuntime createContainerRuntime() {
        Thread thread = Thread.currentThread();
        containers = new FrameworkContainerRuntime(() -> thread.equals(Thread.currentThread()), (viewer, text) -> { });
        return containers;
    }

    @BeforeEach void listen() {
        server.getPluginManager().registerEvents(new ChestListener(runtime), MockBukkit.createMockPlugin());
    }

    @ParameterizedTest
    @CsvSource({"false,false,cancel", "false,true,cancel", "true,false,cancel", "true,true,cancel",
            "false,false,null", "true,false,null", "false,false,throw", "true,false,throw",
            "false,false,dead", "true,false,dead", "false,false,offline", "true,false,offline"})
    void failedFirstOpenAndReopenEndAndCommitBeforeTheSameKeyIsReadAgain(boolean reopen, boolean old, String failure) {
        SyncPlayer viewer = spy(player);
        AtomicReference<List<ItemStack>> stored = new AtomicReference<>(List.of(ItemStack.empty()));
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger results = new AtomicInteger();
        SlotContainer box = service.container(ContainerSpec.storage(1).disposition(Disposition.COMMIT)
                .baseline(stored.get()).store(snapshot -> {
                    stored.set(snapshot.contents());
                    writes.incrementAndGet();
                    return CompletableFuture.completedFuture(new StoreAnswer.Written());
                }).undelivered((id, items) -> { }).build());
        Grid first = grid(box, BindingMode.EDITABLE);
        if (old) { viewer.openInventory(server.createInventory(null, 9)); }
        GridSession session;
        if (reopen) {
            session = service.show(viewer, first);
            tick();
        } else {
            session = service.show(viewer, first);
        }
        viewer.getOpenInventory().setCursor(stack(Material.STONE, 8));
        assertThat(containers.engine().apply(first, viewer, new PlayerViewerItems(viewer.getOpenInventory(), viewer),
                new Gesture.Place(GridSlot.ofIndex(0), false))).isTrue();
        session.result().thenAccept(result -> results.incrementAndGet());
        reject(viewer, failure);
        if (reopen) {
            session.update(Grid.builder(Text.plain("Rejected"), 1)
                    .bind(GridSlot.ofIndex(0), box, 0, BindingMode.EDITABLE).build());
            tick();
        }
        tick();
        if (failure.equals("offline")) {
            // Retirement transfers pending open/update work to the global pump before it can reject the open.
            clock.advanceTicks(4);
        }
        assertThat(session.isOpen()).isFalse();
        tick();
        assertThat(session.result().toCompletableFuture())
                .isCompletedWithValue(new UiResult.Closed<Ack>(CloseReason.INTERRUPTED));
        assertThat(writes).hasValue(1);
        assertThat(results).hasValue(1);
        assertThat(box.state()).isEqualTo(ContainerState.ENDED);
        assertThat(box.isViewed()).isFalse();
        assertThat(stored.get()).containsExactly(stack(Material.STONE, 8));
        doReturn(true).when(viewer).isOnline();
        doReturn(false).when(viewer).isDead();
        doAnswer(call -> call.callRealMethod()).when(viewer).openInventory(any(Inventory.class));
        SlotContainer next = service.container(ContainerSpec.storage(1).disposition(Disposition.COMMIT)
                .baseline(stored.get()).store(snapshot -> CompletableFuture.completedFuture(new StoreAnswer.Written()))
                .undelivered((id, items) -> { }).build());
        GridSession nextSession = service.show(viewer, grid(next, BindingMode.EDITABLE));
        tick();
        assertThat(nextSession.isOpen()).isTrue();
        assertThat(viewer.getOpenInventory().getTopInventory().getItem(0)).isEqualTo(stack(Material.STONE, 8));
        assertThat(writes).hasValue(1);
    }

    private void reject(SyncPlayer viewer, String failure) {
        if (failure.equals("dead")) { doReturn(true).when(viewer).isDead(); }
        if (failure.equals("offline")) { doReturn(false).when(viewer).isOnline(); }
        if (failure.equals("cancel")) {
            AtomicBoolean cancelNext = new AtomicBoolean(true);
            server.getPluginManager().registerEvents(new Listener() {
                @EventHandler public void cancel(InventoryOpenEvent event) {
                    if (cancelNext.compareAndSet(true, false)) { event.setCancelled(true); }
                }
            }, MockBukkit.createMockPlugin());
        }
        doAnswer(call -> {
            boolean hadContainer = !viewer.getOpenInventory().getType().equals(InventoryType.CRAFTING);
            if (hadContainer) { viewer.closeInventory(InventoryCloseEvent.Reason.OPEN_NEW); }
            if (failure.equals("throw")) { throw new IllegalStateException("open refused"); }
            InventoryView attempted = mock(InventoryView.class);
            when(attempted.getTopInventory()).thenReturn(call.getArgument(0));
            when(attempted.getPlayer()).thenReturn(viewer);
            InventoryOpenEvent event = new InventoryOpenEvent(attempted);
            server.getPluginManager().callEvent(event);
            // This is Paper's post-cancellation non-null fallback, which MockBukkit does not model.
            return hadContainer && !failure.equals("null") ? viewer.getOpenInventory() : null;
        }).when(viewer).openInventory(any(Inventory.class));
    }
}
