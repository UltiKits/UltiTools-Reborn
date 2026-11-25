package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.ui.chest.ChestListener;
import com.ultikits.ultitools.ui.container.FrameworkContainerRuntime;
import com.ultikits.ultitools.ui.runtime.ContainerRuntime;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises display copies with the real container engine, without changing its item-transfer rules. */
class ChestGestureRedrawTest extends ChestTestFixture {
    @Override
    ContainerRuntime createContainerRuntime() {
        Thread containerThread = Thread.currentThread();
        return new FrameworkContainerRuntime(() -> containerThread.equals(Thread.currentThread()), (viewer, text) -> { });
    }

    @BeforeEach
    void registerListener() {
        server.getPluginManager().registerEvents(new ChestListener(runtime), MockBukkit.createMockPlugin());
    }

    @ParameterizedTest
    @CsvSource({
            "PLACE,LEFT,PLACE_ALL", "PLACE,RIGHT,PLACE_ONE",
            "PICKUP,LEFT,PICKUP_ALL", "PICKUP,RIGHT,PICKUP_HALF",
            "SWAP,LEFT,SWAP_WITH_CURSOR",
            "SHIFT_IN,SHIFT_LEFT,MOVE_TO_OTHER_INVENTORY", "SHIFT_IN,SHIFT_RIGHT,MOVE_TO_OTHER_INVENTORY",
            "SHIFT_OUT,SHIFT_LEFT,MOVE_TO_OTHER_INVENTORY", "SHIFT_OUT,SHIFT_RIGHT,MOVE_TO_OTHER_INVENTORY",
            "HOTBAR,NUMBER_KEY,HOTBAR_SWAP", "HOTBAR,NUMBER_KEY,HOTBAR_MOVE_AND_READD"
    })
    void everyAppliedClickRefreshesTheActorsDisplayBeforeTheListenerReturns(String gesture, ClickType click,
                                                                          InventoryAction action) {
        boolean put = gesture.equals("PLACE") || gesture.equals("SHIFT_IN");
        SlotContainer box = container(1, List.of(put ? ItemStack.empty() : stack(Material.STONE, 8)));
        GridSession session = open(grid(box, BindingMode.EDITABLE));
        ItemStack before = box.get(0);
        configure(gesture);
        int raw = gesture.equals("SHIFT_IN") ? top().getSize() : 0;
        InventoryClickEvent event = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                raw, click, action, click == ClickType.NUMBER_KEY ? 2 : -1);
        server.getPluginManager().callEvent(event);
        assertThat(event.isCancelled()).isTrue();
        assertThat(box.get(0)).isNotEqualTo(before);
        assertThat(displayed(top(), 0)).isEqualTo(box.get(0));
        assertThat(runtime.picture(session).get(GridSlot.ofIndex(0))).isEqualTo(box.get(0));
        assertThat(player.syncScopes).isEmpty();
    }

    @Test
    void anotherViewerStillRefreshesOnTheNextTickRatherThanInsideTheActorsClick() {
        SlotContainer box = container(1, List.of(ItemStack.empty()));
        open(grid(box, BindingMode.EDITABLE));
        SyncPlayer other = new SyncPlayer(server, "Bob");
        server.addPlayer(other);
        service.show(other, grid(box, BindingMode.VIEW));
        tick();
        Inventory otherTop = other.getOpenInventory().getTopInventory();
        player.setItemOnCursor(stack(Material.STONE, 8));
        server.getPluginManager().callEvent(new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                0, ClickType.LEFT, InventoryAction.PLACE_ALL));
        assertThat(displayed(top(), 0)).isEqualTo(stack(Material.STONE, 8));
        assertThat(displayed(otherTop, 0).isEmpty()).isTrue();
        tick();
        assertThat(displayed(otherTop, 0)).isEqualTo(stack(Material.STONE, 8));
    }

    @Test
    void deferredDragRefreshesItsDisplayInTheSameTickThatItSpendsTheRestoredCursor() {
        SlotContainer box = container(2, List.of(ItemStack.empty(), ItemStack.empty()));
        open(grid(box, BindingMode.EDITABLE));
        InventoryView view = player.getOpenInventory();
        ItemStack oldCursor = stack(Material.STONE, 8);
        view.setCursor(ItemStack.empty());
        InventoryDragEvent event = new InventoryDragEvent(view, ItemStack.empty(), oldCursor, false,
                Map.of(0, stack(Material.STONE, 4), 1, stack(Material.STONE, 4)));
        server.getPluginManager().callEvent(event);
        assertThat(event.isCancelled()).isTrue();
        view.setCursor(oldCursor);
        assertThat(box.get(0).isEmpty()).isTrue();
        assertThat(displayed(top(), 0).isEmpty()).isTrue();
        tick();
        assertThat(box.contents()).containsExactly(stack(Material.STONE, 4), stack(Material.STONE, 4));
        assertThat(displayed(top(), 0)).isEqualTo(box.get(0));
        assertThat(displayed(top(), 1)).isEqualTo(box.get(1));
        assertThat(player.getItemOnCursor().isEmpty()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"SWAP_OFFHAND,HOTBAR_SWAP", "DOUBLE_CLICK,COLLECT_TO_CURSOR", "DROP,DROP_ONE_SLOT",
            "CONTROL_DROP,DROP_ALL_SLOT", "MIDDLE,CLONE_STACK", "CREATIVE,CLONE_STACK"})
    void disallowedBoundSlotGesturesKeepTheContainerAndDisplayUnchanged(ClickType click, InventoryAction action) {
        SlotContainer box = container(1, List.of(stack(Material.STONE, 8)));
        open(grid(box, BindingMode.EDITABLE));
        InventoryClickEvent event = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                0, click, action);
        server.getPluginManager().callEvent(event);
        assertThat(event.isCancelled()).isTrue();
        assertThat(box.get(0)).isEqualTo(stack(Material.STONE, 8));
        assertThat(displayed(top(), 0)).isEqualTo(box.get(0));
        assertThat(player.getItemOnCursor().isEmpty()).isTrue();
    }

    private void configure(String gesture) {
        switch (gesture) {
            case "PLACE" -> player.setItemOnCursor(stack(Material.STONE, 8));
            case "SWAP" -> player.setItemOnCursor(stack(Material.DIRT, 4));
            case "SHIFT_IN" -> player.getInventory().setItem(9, stack(Material.STONE, 8));
            case "HOTBAR" -> player.getInventory().setItem(2, stack(Material.DIRT, 4));
            default -> { /* Pickup and quick-move-out start with an empty cursor and inventory. */ }
        }
    }

    private static ItemStack displayed(Inventory inventory, int slot) {
        ItemStack item = inventory.getItem(slot);
        return item == null || item.isEmpty() ? ItemStack.empty() : item;
    }
}
