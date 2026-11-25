package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.chest.ChestListener;
import com.ultikits.ultitools.ui.chest.PlayerViewerItems;
import com.ultikits.ultitools.ui.container.FrameworkContainerRuntime;
import com.ultikits.ultitools.ui.runtime.ContainerRuntime;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;

class ChestCloseSafetyTest extends ChestTestFixture {
    private final AtomicInteger writes = new AtomicInteger();
    private final AtomicInteger results = new AtomicInteger();

    @Override ContainerRuntime createContainerRuntime() {
        Thread thread = Thread.currentThread();
        return new FrameworkContainerRuntime(() -> thread.equals(Thread.currentThread()), (viewer, text) -> { });
    }

    @BeforeEach void listen() {
        server.getPluginManager().registerEvents(new ChestListener(runtime), MockBukkit.createMockPlugin());
    }

    @ParameterizedTest
    @EnumSource(InventoryCloseEvent.Reason.class)
    void everyPaperCloseCommitsOnTheOriginalCleanupTickExactlyOnce(InventoryCloseEvent.Reason reason) {
        SlotContainer box = box();
        GridSession session = dirty(player, box, ShowOptions.defaults());
        player.closeInventory(reason);
        assertThat(writes).hasValue(0);
        tick();
        assertThat(writes).hasValue(1);
        tick();
        CloseReason expected = reason == InventoryCloseEvent.Reason.PLAYER ? CloseReason.PLAYER
                : reason == InventoryCloseEvent.Reason.DISCONNECT ? CloseReason.DISCONNECTED : CloseReason.INTERRUPTED;
        assertThat(session.result().toCompletableFuture()).isCompletedWithValue(new UiResult.Closed<Ack>(expected));
        assertThat(results).hasValue(1);
        assertThat(box.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.COMMITTED);
    }

    @ParameterizedTest
    @EnumSource(InventoryCloseEvent.Reason.class)
    void unchangedStorageNeverWritesForAnyPaperClose(InventoryCloseEvent.Reason reason) {
        SlotContainer box = box();
        GridSession session = open(grid(box, BindingMode.EDITABLE));
        player.closeInventory(reason);
        tick();
        tick();
        assertThat(session.isOpen()).isFalse();
        assertThat(writes).hasValue(0);
        assertThat(box.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.UNCHANGED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameTickQuitWinsCantUseAndRetiredEntityStillSaves(boolean retired) {
        SyncPlayer viewer = spy(player);
        SlotContainer box = box();
        GridSession session = dirty(viewer, box, ShowOptions.defaults());
        viewer.closeInventory(InventoryCloseEvent.Reason.CANT_USE);
        assertThat(session.isOpen()).isTrue();
        if (retired) { doReturn(false).when(viewer).isOnline(); }
        runtime.quit(viewer);
        assertThat(session.isOpen()).isFalse();
        tick();
        tick();
        tick();
        assertThat(session.result().toCompletableFuture())
                .isCompletedWithValue(new UiResult.Closed<Ack>(CloseReason.DISCONNECTED));
        assertThat(writes).hasValue(1);
        assertThat(results).hasValue(1);
        assertThat(box.isViewed()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"code,CLOSED_BY_CODE", "unload,OWNER_UNLOADED", "replace,INTERRUPTED", "duplicate,INTERRUPTED"})
    void aSecondTerminalSignalCannotDuplicateCantUseCommit(String signal, CloseReason expected) {
        SlotContainer box = box();
        GridSession session = dirty(player, box, ShowOptions.defaults());
        InventoryCloseEvent close = new InventoryCloseEvent(player.getOpenInventory(), InventoryCloseEvent.Reason.CANT_USE);
        server.getPluginManager().callEvent(close);
        switch (signal) {
            case "code" -> session.close();
            case "unload" -> runtime.unload(service);
            case "replace" -> service.show(player, Grid.builder(Text.plain("Replacement"), 1).build());
            default -> server.getPluginManager().callEvent(close);
        }
        tick();
        tick();
        assertThat(session.result().toCompletableFuture()).isCompletedWithValue(new UiResult.Closed<Ack>(expected));
        assertThat(writes).hasValue(1);
        assertThat(results).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timeoutAndCantUseChooseOnlyOneResult(boolean timeoutFirst) {
        SlotContainer box = box();
        GridSession session = dirty(player, box, ShowOptions.builder().timeout(Duration.ofMillis(50)).build());
        InventoryCloseEvent close = new InventoryCloseEvent(player.getOpenInventory(), InventoryCloseEvent.Reason.CANT_USE);
        if (timeoutFirst) { tick(); }
        server.getPluginManager().callEvent(close);
        tick();
        tick();
        assertThat(session.isOpen()).isFalse();
        assertThat(writes).hasValue(1);
        assertThat(results).hasValue(1);
        assertThat(session.result().toCompletableFuture()).isCompletedWithValue(new UiResult.Closed<Ack>(CloseReason.TIMED_OUT));
    }

    private SlotContainer box() {
        return service.container(ContainerSpec.storage(1).disposition(Disposition.COMMIT).store(snapshot -> {
            writes.incrementAndGet();
            return CompletableFuture.completedFuture(new StoreAnswer.Written());
        }).undelivered((id, items) -> { }).build());
    }

    private GridSession dirty(SyncPlayer viewer, SlotContainer box, ShowOptions options) {
        GridSession session = service.show(viewer, grid(box, BindingMode.EDITABLE), options);
        tick();
        session.result().thenAccept(result -> results.incrementAndGet());
        viewer.setItemOnCursor(stack(Material.STONE, 8));
        boolean applied = runtime.applyGesture(session, new PlayerViewerItems(viewer.getOpenInventory(), viewer),
                new Gesture.Place(GridSlot.ofIndex(0), false));
        if (!applied) { throw new IllegalStateException("Test gesture refused"); }
        return session;
    }
}
