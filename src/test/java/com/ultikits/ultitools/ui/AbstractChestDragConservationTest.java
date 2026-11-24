package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.ui.chest.ChestListener;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Override createContainerRuntime in a concrete subclass to run the same event-order suite on another engine. */
abstract class AbstractChestDragConservationTest extends ChestTestFixture {
    @BeforeEach void registerDragListener() {
        server.getPluginManager().registerEvents(new ChestListener(runtime), MockBukkit.createMockPlugin());
    }

    @ParameterizedTest
    @CsvSource({"false,9,0,3,0,3,3", "true,64,0,3,61,1,1", "false,9,0,1,0,9,9",
            "false,64,60,2,28,64,32"})
    void paperRestorationThenDeferredSpreadConservesEveryItem(boolean single, int cursor, int baseline,
            int slots, int remainder, int first, int second) {
        SlotContainer box = container(3, List.of(baseline == 0 ? ItemStack.empty() : stack(Material.STONE, baseline),
                ItemStack.empty(), ItemStack.empty()));
        open(grid(box, BindingMode.EDITABLE));
        player.getInventory().setItem(12, stack(Material.STONE, 7));
        player.setItemOnCursor(stack(Material.STONE, cursor));
        int before = total(box);
        Map<Integer, ItemStack> targets = new LinkedHashMap<>();
        for (int index = 0; index < slots; index++) {
            targets.put(index, stack(Material.STONE, index == 0 ? first : second));
        }
        InventoryDragEvent event = paperDrag(single, targets, remainder);
        assertThat(event.isCancelled()).isTrue();
        assertThat(total(box)).as("Paper restores the original cursor before the next entity tick").isEqualTo(before);
        assertThat(box.get(0).getAmount()).isEqualTo(baseline);
        tick();
        assertThat(total(box)).isEqualTo(before);
        assertThat(box.get(0)).isEqualTo(stack(Material.STONE, first));
        if (slots > 1) { assertThat(box.get(1)).isEqualTo(stack(Material.STONE, second)); }
        assertThat(player.getItemOnCursor().getAmount()).isEqualTo(remainder);
        assertThat(player.getInventory().getItem(12)).isEqualTo(stack(Material.STONE, 7));
        assertThat(player.syncScopes).isNotEmpty().allSatisfy(scope -> {
            assertThat(scope.kind()).isEqualTo(RecordingScheduler.Scope.Kind.ENTITY);
            assertThat(scope.entity()).isEqualTo(player.getUniqueId());
        });
    }

    @Test void mixedTopAndBottomDragNeverMovesItems() {
        SlotContainer box = container(1, List.of(ItemStack.empty()));
        open(grid(box, BindingMode.EDITABLE));
        player.setItemOnCursor(stack(Material.STONE, 8));
        int before = total(box);
        assertThat(paperDrag(false, Map.of(0, stack(Material.STONE, 4), 9, stack(Material.STONE, 4)), 0)
                .isCancelled()).isTrue();
        tick();
        assertThat(total(box)).isEqualTo(before);
        assertThat(box.get(0).isEmpty()).isTrue();
        assertThat(player.getItemOnCursor()).isEqualTo(stack(Material.STONE, 8));
    }

    @Test void closingBeforeTheDeferredDragMovesNothing() {
        SlotContainer box = container(1, List.of(ItemStack.empty()));
        GridSession session = open(grid(box, BindingMode.EDITABLE));
        player.setItemOnCursor(stack(Material.STONE, 8));
        int before = total(box);
        assertThat(paperDrag(true, Map.of(0, stack(Material.STONE, 1)), 7).isCancelled()).isTrue();
        session.close();
        returnCursorBeforeMockClose();
        tick();
        assertThat(session.isOpen()).isFalse();
        assertThat(total(box)).isEqualTo(before);
        assertThat(box.get(0).isEmpty()).isTrue();
    }

    @Test void unloadingBeforeTheDeferredDragMovesNothing() {
        SlotContainer box = container(1, List.of(ItemStack.empty()));
        GridSession session = open(grid(box, BindingMode.EDITABLE));
        player.setItemOnCursor(stack(Material.STONE, 8));
        int before = total(box);
        assertThat(paperDrag(true, Map.of(0, stack(Material.STONE, 1)), 7).isCancelled()).isTrue();
        returnCursorBeforeMockClose();
        runtime.unload(service);
        tick();
        assertThat(session.isOpen()).isFalse();
        assertThat(box.state()).isEqualTo(ContainerState.ENDED);
        assertThat(total(box)).isEqualTo(before);
        assertThat(box.get(0).isEmpty()).isTrue();
    }

    @Test void aReboundTargetAbortsTheWholeDeferredGesture() {
        SlotContainer box = container(2, List.of(ItemStack.empty(), ItemStack.empty()));
        SlotContainer replacement = container(1, List.of(ItemStack.empty()));
        GridSession session = open(grid(box, BindingMode.EDITABLE));
        // Reserve the viewer lane first; the module update below will render before the delayed drag.
        session.update(session.current());
        player.setItemOnCursor(stack(Material.STONE, 8));
        int before = total(box);
        assertThat(paperDrag(true, Map.of(0, stack(Material.STONE, 1), 1, stack(Material.STONE, 1)), 6)
                .isCancelled()).isTrue();
        session.update(Grid.builder(session.current().title(), 1)
                .bind(GridSlot.ofIndex(0), box, 0, BindingMode.EDITABLE)
                .bind(GridSlot.ofIndex(1), replacement, 0, BindingMode.EDITABLE).build());
        tick();
        assertThat(total(box) + amount(replacement.get(0))).isEqualTo(before);
        assertThat(box.contents()).allSatisfy(item -> assertThat(item.isEmpty()).isTrue());
        assertThat(replacement.get(0).isEmpty()).isTrue();
        assertThat(player.getItemOnCursor()).isEqualTo(stack(Material.STONE, 8));
    }

    @Test void changingOnlyTheContainerIndexAlsoAbortsTheDeferredGesture() {
        SlotContainer box = container(2, List.of(ItemStack.empty(), ItemStack.empty()));
        GridSession session = open(grid(box, BindingMode.EDITABLE));
        session.update(session.current());
        player.setItemOnCursor(stack(Material.STONE, 8));
        assertThat(paperDrag(true, Map.of(0, stack(Material.STONE, 1)), 7).isCancelled()).isTrue();
        session.update(Grid.builder(session.current().title(), 1)
                .bind(GridSlot.ofIndex(0), box, 1, BindingMode.EDITABLE).build());
        tick();
        assertThat(total(box)).isEqualTo(8);
        assertThat(box.contents()).allSatisfy(item -> assertThat(item.isEmpty()).isTrue());
    }

    @Test void aChangedCursorAbortsInsteadOfSpendingTheReplacementStack() {
        SlotContainer box = container(1, List.of(ItemStack.empty()));
        open(grid(box, BindingMode.EDITABLE));
        player.setItemOnCursor(stack(Material.STONE, 8));
        assertThat(paperDrag(true, Map.of(0, stack(Material.STONE, 1)), 7).isCancelled()).isTrue();
        player.getInventory().setItem(12, player.getItemOnCursor());
        player.setItemOnCursor(stack(Material.DIRT, 3));
        tick();
        assertThat(box.get(0).isEmpty()).isTrue();
        assertThat(player.getInventory().getItem(12)).isEqualTo(stack(Material.STONE, 8));
        assertThat(player.getItemOnCursor()).isEqualTo(stack(Material.DIRT, 3));
    }

    @Test void anAlreadyCancelledDragNeverQueuesAMove() {
        SlotContainer box = container(1, List.of(ItemStack.empty()));
        open(grid(box, BindingMode.EDITABLE));
        player.setItemOnCursor(stack(Material.STONE, 8));
        InventoryDragEvent event = new InventoryDragEvent(player.getOpenInventory(), stack(Material.STONE, 7),
                stack(Material.STONE, 8), true, Map.of(0, stack(Material.STONE, 1)));
        event.setCancelled(true);
        server.getPluginManager().callEvent(event);
        tick();
        assertThat(event.isCancelled()).isTrue();
        assertThat(total(box)).isEqualTo(8);
        assertThat(box.get(0).isEmpty()).isTrue();
    }

    private InventoryDragEvent paperDrag(boolean single, Map<Integer, ItemStack> targets, int remaining) {
        InventoryView view = player.getOpenInventory();
        ItemStack oldCursor = view.getCursor().clone();
        ItemStack remainder = remaining == 0 ? ItemStack.empty() : stack(oldCursor.getType(), remaining);
        view.setCursor(remainder);
        InventoryDragEvent event = new InventoryDragEvent(view, remainder, oldCursor, single, targets);
        server.getPluginManager().callEvent(event);
        if (event.isCancelled()) { view.setCursor(oldCursor); }
        return event;
    }

    private void returnCursorBeforeMockClose() {
        // MockBukkit closeInventory clears the cursor without Paper's normal inventory return.
        player.getInventory().addItem(player.getItemOnCursor());
        player.setItemOnCursor(ItemStack.empty());
    }

    private int total(SlotContainer box) {
        int count = amount(player.getItemOnCursor());
        for (ItemStack item : box.contents()) { count += amount(item); }
        for (ItemStack item : player.getInventory().getStorageContents()) { count += amount(item); }
        return count;
    }

    private static int amount(ItemStack item) { return item == null || item.isEmpty() ? 0 : item.getAmount(); }
}
