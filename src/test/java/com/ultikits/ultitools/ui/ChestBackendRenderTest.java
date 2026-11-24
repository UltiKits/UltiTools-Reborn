package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.text.Text;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class ChestBackendRenderTest extends ChestTestFixture {
    @Test void supportsOnlyGridsWithTheFrameworkPriorityAndIdentity() {
        Grid grid = Grid.builder(Text.plain("grid"), 1).build();
        assertThat(backend.supports(grid, player)).isTrue();
        assertThat(backend.supports(Notice.builder(Text.plain("notice")).build(), player)).isFalse();
        assertThat(backend.id()).isEqualTo(Key.key("ultitools", "chest"));
        assertThat(backend.priority()).isZero();
    }

    @Test void openAndUpdateWriteOnlyDifferentSlotsIncludingRemovedIcons() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(9), any(Component.class)))
                    .thenAnswer(call -> spy(server.createInventory(call.getArgument(0), 9, (Component) call.getArgument(2))));
            Grid first = Grid.builder(Text.plain("Chest"), 1)
                    .set(0, 0, ItemSpec.builder(Material.STONE).build(), null).build();
            GridSession session = open(first);
            Inventory inventory = top();
            verify(inventory, times(1)).setItem(eq(0), any(ItemStack.class));
            verify(inventory, never()).setItem(eq(1), any(ItemStack.class));
            clearInvocations(inventory);
            Grid next = Grid.builder(first.title(), 1).set(0, 0, ItemSpec.builder(Material.STONE).build(), null)
                    .set(0, 1, ItemSpec.builder(Material.DIRT).build(), null).build();
            session.update(next);
            tick();
            verify(inventory, never()).setItem(eq(0), any(ItemStack.class));
            verify(inventory, times(1)).setItem(eq(1), any(ItemStack.class));
            clearInvocations(inventory);
            session.update(Grid.builder(first.title(), 1).build());
            tick();
            verify(inventory).setItem(eq(0), any(ItemStack.class));
            verify(inventory).setItem(eq(1), any(ItemStack.class));
            assertThat(inventory.isEmpty()).isTrue();
        }
    }

    @Test void replacingOnlyTheActionDoesNotRewriteSlotsOrTouchTheCursorAndOpenView() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(9), any(Component.class)))
                    .thenAnswer(call -> spy(server.createInventory(call.getArgument(0), 9, (Component) call.getArgument(2))));
            SlotContainer box = container(1, List.of(stack(Material.STONE, 8)));
            AtomicInteger firstClicks = new AtomicInteger();
            AtomicInteger nextClicks = new AtomicInteger();
            Grid first = Grid.builder(Text.plain("Actions"), 1)
                    .set(0, 0, ItemSpec.builder(Material.DIAMOND).build(), event -> firstClicks.incrementAndGet())
                    .bind(GridSlot.ofIndex(1), box, 0, BindingMode.EDITABLE).build();
            Grid next = Grid.builder(first.title(), 1)
                    .set(0, 0, ItemSpec.builder(Material.DIAMOND).build(), event -> nextClicks.incrementAndGet())
                    .bind(GridSlot.ofIndex(1), box, 0, BindingMode.EDITABLE).build();
            GridSlot icon = GridSlot.ofIndex(0);
            assertThat(first.items().get(icon).icon()).isEqualTo(next.items().get(icon).icon());
            assertThat(first.items().get(icon)).isNotEqualTo(next.items().get(icon));
            GridSession session = open(first);
            Inventory inventory = top();
            InventoryView view = player.getOpenInventory();
            player.setItemOnCursor(stack(Material.DIRT, 3));
            player.getInventory().setItem(0, stack(Material.STONE, 4));
            ItemStack[] storage = player.getInventory().getStorageContents();
            clearInvocations(inventory);

            session.update(next);
            tick();
            tick();

            verify(inventory, never()).setItem(anyInt(), any(ItemStack.class));
            verify(inventory, never()).setContents(any(ItemStack[].class));
            bukkit.verify(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(9), any(Component.class)), times(1));
            assertThat(player.getOpenInventory()).isSameAs(view);
            assertThat(top()).isSameAs(inventory);
            assertThat(player.getItemOnCursor()).isEqualTo(stack(Material.DIRT, 3));
            assertThat(player.getInventory().getStorageContents()).containsExactly(storage);
            assertThat(inventory.getItem(0)).isEqualTo(stack(Material.DIAMOND, 1));
            assertThat(inventory.getItem(1)).isEqualTo(stack(Material.STONE, 8));
            assertThat(session.current()).isSameAs(next);
            assertThat(session.current().items().get(icon).action()).isEqualTo(next.items().get(icon).action());
            assertThat(session.result().toCompletableFuture()).isNotDone();
            assertThat(session.isOpen()).isTrue();
            assertThat(player.syncScopes).isEmpty();
            assertThat(firstClicks).hasValue(0);
            assertThat(nextClicks).hasValue(0);
        }
    }

    @Test void boundPicturesAreCopiesAndChangesRedrawWithoutGridUpdates() {
        SlotContainer box = container(1, List.of(stack(Material.STONE, 8)));
        GridSession session = open(grid(box, BindingMode.EDITABLE));
        ItemStack displayed = top().getItem(0);
        assertThat(displayed).isEqualTo(stack(Material.STONE, 8));
        displayed.setAmount(1);
        assertThat(box.get(0).getAmount()).isEqualTo(8);
        box.load(List.of(stack(Material.DIRT, 3)));
        tick();
        assertThat(top().getItem(0)).isEqualTo(stack(Material.DIRT, 3));
        backend.redraw(session, Map.of(GridSlot.ofIndex(0), stack(Material.STONE, 2)));
        assertThat(top().getItem(0)).isEqualTo(stack(Material.STONE, 2));
    }

    @Test void containerEndingAfterShowBeforeOpenRendersEmpty() {
        SlotContainer box = container(1, List.of(stack(Material.STONE, 8)));
        GridSession session = service.show(player, grid(box, BindingMode.EDITABLE));
        box.end();
        tick();
        assertThat(session.isOpen()).isTrue();
        assertThat(top().isEmpty()).isTrue();
        backend.redraw(session, Map.of(GridSlot.ofIndex(0), stack(Material.STONE, 8)));
        assertThat(top().isEmpty()).isTrue();
    }

    @Test void pagingAnUnchangedContainerThenReplacingItEndsUnchangedWithoutWriting() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        SlotContainer box = service.container(ContainerSpec.storage(18).disposition(Disposition.COMMIT)
                .baseline(Collections.nCopies(18, ItemStack.empty()))
                .store(snapshot -> {
                    writes.incrementAndGet();
                    return CompletableFuture.completedFuture(new StoreAnswer.Written());
                }).undelivered((contributor, items) -> { /* This unchanged container owes no items. */ }).build());
        GridSession session = open(grid(box, BindingMode.EDITABLE));
        session.update(Grid.builder(session.current().title(), 1)
                .bindRange(0, 0, 9, box, 9, BindingMode.EDITABLE).build());
        tick();
        assertThat(box.state()).isEqualTo(ContainerState.EDITABLE);
        session.update(grid(box, BindingMode.EDITABLE));
        tick();
        open(Grid.builder(Text.plain("Replacement"), 1).build());
        assertThat(box.end().toCompletableFuture().get(2, TimeUnit.SECONDS)).isEqualTo(EndOutcome.UNCHANGED);
        assertThat(writes).hasValue(0);
        assertThat(session.isOpen()).isFalse();
    }

    @Test void closingAnOldSessionDoesNotCloseAForeignInventory() {
        GridSession session = open(Grid.builder(Text.plain("Chest"), 1).build());
        Inventory foreign = server.createInventory(null, 9, Component.text("Foreign"));
        player.openInventory(foreign);
        session.close();
        tick();
        assertThat(top()).isSameAs(foreign);
        backend.update(session, session.current(), session.current());
        backend.redraw(session, Map.of());
        backend.close(session);
        assertThat(top()).isSameAs(foreign);
    }
}
