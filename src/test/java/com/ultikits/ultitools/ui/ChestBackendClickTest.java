package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.chest.ChestListener;
import com.ultikits.ultitools.ui.chest.PlayerViewerItems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class ChestBackendClickTest extends ChestTestFixture {
    private Plugin plugin;
    private final AtomicLong monotonic = new AtomicLong();

    @BeforeEach void registerListener() {
        plugin = MockBukkit.createMockPlugin();
        server.getPluginManager().registerEvents(new ChestListener(runtime, monotonic::get), plugin);
    }

    @Test void topPickupIsCancelledBeforeModuleListenersAndUsesTheGestureEntryPoint() {
        SlotContainer box = container(1, List.of(stack(Material.STONE, 8)));
        GridSession session = open(grid(box, BindingMode.EDITABLE));
        CancellationObserver observer = new CancellationObserver();
        server.getPluginManager().registerEvents(observer, plugin);
        InventoryClickEvent event = click(0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        assertThat(event.isCancelled()).isTrue();
        assertThat(observer.cancelled).containsExactly(true);
        assertThat(box.get(0).isEmpty()).isTrue();
        assertThat(player.getItemOnCursor()).isEqualTo(stack(Material.STONE, 8));
        assertThat(player.syncScopes).isEmpty();
        tick();
        assertThat(player.syncScopes).hasSize(1);
        assertThat(player.syncScopes.getFirst().kind()).isEqualTo(RecordingScheduler.Scope.Kind.ENTITY);
        assertThat(player.syncScopes.getFirst().entity()).isEqualTo(player.getUniqueId());
        assertThat(session.isOpen()).isTrue();
    }

    @Test void leftRightAndSwapReadRealContentsInsteadOfTheScreenCopyOrAction() {
        SlotContainer box = container(2, List.of(stack(Material.STONE, 8), ItemStack.empty()));
        open(grid(box, BindingMode.EDITABLE));
        top().setItem(0, stack(Material.DIRT, 64));
        click(0, ClickType.RIGHT, InventoryAction.UNKNOWN);
        assertThat(player.getItemOnCursor()).isEqualTo(stack(Material.STONE, 4));
        assertThat(box.get(0)).isEqualTo(stack(Material.STONE, 4));
        click(1, ClickType.RIGHT, InventoryAction.NOTHING);
        assertThat(box.get(1)).isEqualTo(stack(Material.STONE, 1));
        assertThat(player.getItemOnCursor()).isEqualTo(stack(Material.STONE, 3));
        player.setItemOnCursor(stack(Material.DIRT, 2));
        click(0, ClickType.RIGHT, InventoryAction.SWAP_WITH_CURSOR);
        assertThat(box.get(0)).isEqualTo(stack(Material.STONE, 4));
        click(0, ClickType.LEFT, InventoryAction.NOTHING);
        assertThat(box.get(0)).isEqualTo(stack(Material.DIRT, 2));
        assertThat(player.getItemOnCursor()).isEqualTo(stack(Material.STONE, 4));
    }

    @Test void shiftMovesInBothDirectionsAndNumberKeysAddressOnlyTheChosenHotbarSlot() {
        SlotContainer box = container(2, List.of(ItemStack.empty(), ItemStack.empty()));
        open(grid(box, BindingMode.EDITABLE));
        player.getInventory().setItem(9, stack(Material.STONE, 10));
        int topSize = top().getSize();
        InventoryClickEvent shiftIn = click(topSize, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        assertThat(shiftIn.getSlot()).isEqualTo(9);
        assertThat(shiftIn.isCancelled()).isTrue();
        assertThat(box.get(0)).isEqualTo(stack(Material.STONE, 10));
        assertThat(player.getInventory().getItem(9).isEmpty()).isTrue();
        click(0, ClickType.SHIFT_RIGHT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        assertThat(box.get(0).isEmpty()).isTrue();
        assertThat(player.getInventory().getItem(0)).isEqualTo(stack(Material.STONE, 10));
        player.getInventory().setItem(2, stack(Material.DIRT, 5));
        InventoryClickEvent number = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                1, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_MOVE_AND_READD, 2);
        server.getPluginManager().callEvent(number);
        assertThat(number.isCancelled()).isTrue();
        assertThat(box.get(1)).isEqualTo(stack(Material.DIRT, 5));
        assertThat(player.getInventory().getItem(2).isEmpty()).isTrue();
        assertThat(player.getInventory().getItem(0)).isEqualTo(stack(Material.STONE, 10));
    }

    @Test void iconsRecordCallbacksButModuleCodeRunsLaterAndEmptyAndViewSlotsNeverMoveItems() {
        SlotContainer box = container(1, List.of(stack(Material.STONE, 8)));
        List<ClickKind> clicks = new ArrayList<>();
        Grid grid = Grid.builder(Text.plain("Icons"), 1)
                .set(0, 0, ItemSpec.builder(Material.DIAMOND).build(), event -> clicks.add(event.kind()))
                .bind(GridSlot.ofIndex(1), box, 0, BindingMode.VIEW).build();
        open(grid);
        assertThat(click(0, ClickType.LEFT, InventoryAction.PICKUP_ALL).isCancelled()).isTrue();
        assertThat(clicks).isEmpty();
        assertThat(click(1, ClickType.LEFT, InventoryAction.PICKUP_ALL).isCancelled()).isTrue();
        assertThat(click(2, ClickType.LEFT, InventoryAction.PLACE_ALL).isCancelled()).isTrue();
        tick();
        assertThat(clicks).containsExactly(ClickKind.LEFT);
        assertThat(box.get(0)).isEqualTo(stack(Material.STONE, 8));
        assertThat(player.getItemOnCursor().isEmpty()).isTrue();
    }

    @Test void anIconDoubleClickSequenceOnlyReportsTheFirstLeftClick() {
        List<ClickKind> clicks = new ArrayList<>();
        open(Grid.builder(Text.plain("Double"), 1).set(0, 0, ItemSpec.builder(Material.DIAMOND).build(),
                event -> clicks.add(event.kind())).build());
        assertThat(click(0, ClickType.LEFT, InventoryAction.PICKUP_ALL).isCancelled()).isTrue();
        assertThat(click(0, ClickType.LEFT, InventoryAction.PICKUP_ALL).isCancelled()).isTrue();
        assertThat(click(0, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR).isCancelled()).isTrue();
        tick();
        assertThat(clicks).containsExactly(ClickKind.LEFT);
    }

    @Test void slowLeftClicksAreIndependentAndOtherSlotsAndRightClicksAreNotDebounced() {
        List<String> clicks = new ArrayList<>();
        open(Grid.builder(Text.plain("Timing"), 1)
                .set(0, 0, ItemSpec.builder(Material.DIAMOND).build(), event -> clicks.add("0:" + event.kind()))
                .set(0, 1, ItemSpec.builder(Material.STONE).build(), event -> clicks.add("1:" + event.kind())).build());
        assertThat(click(0, ClickType.LEFT, InventoryAction.PICKUP_ALL).isCancelled()).isTrue();
        monotonic.set(249_999_999L);
        click(0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        click(1, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        click(0, ClickType.RIGHT, InventoryAction.PICKUP_HALF);
        click(0, ClickType.RIGHT, InventoryAction.PICKUP_HALF);
        monotonic.set(250_000_000L);
        click(0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        tick();
        assertThat(clicks).containsExactly("0:LEFT", "1:LEFT", "0:RIGHT", "0:RIGHT", "0:LEFT");
    }

    @Test void alreadyCancelledIconInputDoesNotRunModuleCode() {
        List<ClickKind> clicks = new ArrayList<>();
        open(Grid.builder(Text.plain("Cancelled"), 1).set(0, 0, ItemSpec.builder(Material.DIAMOND).build(),
                event -> clicks.add(event.kind())).build());
        InventoryClickEvent event = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        event.setCancelled(true);
        server.getPluginManager().callEvent(event);
        tick();
        assertThat(event.isCancelled()).isTrue();
        assertThat(clicks).isEmpty();
    }

    @Test void bottomWhitelistAndOutsideDropsStayAllowedButCollectAndBundleAreBlocked() {
        SlotContainer box = container(1, List.of(stack(Material.STONE, 8)));
        open(grid(box, BindingMode.EDITABLE));
        assertThat(click(9, ClickType.LEFT, InventoryAction.PICKUP_ALL).isCancelled()).isFalse();
        assertThat(click(-999, ClickType.WINDOW_BORDER_LEFT, InventoryAction.DROP_ALL_CURSOR).isCancelled()).isFalse();
        assertThat(click(9, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR).isCancelled()).isTrue();
        assertThat(click(0, ClickType.LEFT, InventoryAction.PICKUP_ALL_INTO_BUNDLE).isCancelled()).isTrue();
        assertThat(box.get(0)).isEqualTo(stack(Material.STONE, 8));
        InventoryClickEvent cancelled = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                9, ClickType.LEFT, InventoryAction.PLACE_ALL);
        cancelled.setCancelled(true);
        server.getPluginManager().callEvent(cancelled);
        assertThat(cancelled.isCancelled()).isTrue();
    }

    @Test void alreadyCancelledInputRemainsCancelledWithoutMovingItems() {
        SlotContainer box = container(1, List.of(stack(Material.STONE, 8)));
        open(grid(box, BindingMode.EDITABLE));
        InventoryClickEvent event = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        event.setCancelled(true);
        server.getPluginManager().callEvent(event);
        assertThat(box.get(0)).isEqualTo(stack(Material.STONE, 8));
        assertThat(player.getItemOnCursor().isEmpty()).isTrue();
        assertThat(event.isCancelled()).isTrue();
    }

    @Test void dragsUseTheWholeTargetSetIncludingOneSlotMixedAreasAndBottomOnly() {
        SlotContainer box = container(3, List.of(ItemStack.empty(), ItemStack.empty(), ItemStack.empty()));
        open(grid(box, BindingMode.EDITABLE));
        player.setItemOnCursor(stack(Material.STONE, 9));
        InventoryDragEvent spread = drag(false, Map.of(0, stack(Material.STONE, 3), 1, stack(Material.STONE, 3), 2, stack(Material.STONE, 3)));
        assertThat(spread.isCancelled()).isTrue();
        tick();
        assertThat(box.contents()).containsExactly(stack(Material.STONE, 3), stack(Material.STONE, 3), stack(Material.STONE, 3));
        assertThat(player.getItemOnCursor().isEmpty()).isTrue();
        player.setItemOnCursor(stack(Material.DIRT, 3));
        assertThat(drag(true, Map.of(3, stack(Material.DIRT, 1))).isCancelled()).isTrue();
        assertThat(drag(false, Map.of(0, stack(Material.DIRT, 1), 9, stack(Material.DIRT, 1))).isCancelled()).isTrue();
        assertThat(drag(false, Map.of(9, stack(Material.DIRT, 3))).isCancelled()).isFalse();
        assertThat(player.getItemOnCursor().isEmpty()).isTrue();
        assertThat(player.getInventory().getItem(9)).isEqualTo(stack(Material.DIRT, 3));
        player.setItemOnCursor(stack(Material.STONE, 3));
        assertThat(drag(true, Map.of(0, stack(Material.STONE, 4))).isCancelled()).isTrue();
        tick();
        assertThat(box.get(0)).isEqualTo(stack(Material.STONE, 4));
        assertThat(player.getItemOnCursor()).isEqualTo(stack(Material.STONE, 2));
        assertThat(player.syncScopes).hasSize(2);
    }

    @Test void terminalSessionCannotMoveOrReportThroughClickOrDrag() {
        SlotContainer box = container(1, List.of(stack(Material.STONE, 8)));
        GridSession session = open(grid(box, BindingMode.EDITABLE));
        session.close();
        assertThat(runtime.applyGesture(session, new PlayerViewerItems(player.getOpenInventory(), player),
                new Gesture.Pickup(GridSlot.ofIndex(0), false))).isFalse();
        assertThat(click(0, ClickType.LEFT, InventoryAction.PICKUP_ALL).isCancelled()).isTrue();
        assertThat(drag(false, Map.of(0, stack(Material.DIRT, 1))).isCancelled()).isTrue();
        assertThat(box.get(0)).isEqualTo(stack(Material.STONE, 8));
        assertThat(player.getItemOnCursor().isEmpty()).isTrue();
        tick();
        assertThat(session.isOpen()).isFalse();
    }

    @Test void unloadingOneServiceLeavesASameNameOwnersChestUsable() throws Exception {
        SlotContainer firstBox = container(1, List.of(stack(Material.STONE, 8)));
        GridSession first = open(grid(firstBox, BindingMode.EDITABLE));
        UiService replacement = runtime.service(service.owner());
        SyncPlayer bob = new SyncPlayer(server, "Bob");
        server.addPlayer(bob);
        SlotContainer secondBox = replacement.container(ContainerSpec.storage(1)
                .disposition(Disposition.RETURN_TO_CONTRIBUTORS).endWhenUnviewed(false)
                .undelivered((contributor, items) -> { /* This test withdraws the baseline before cleanup. */ })
                .baseline(List.of(stack(Material.DIRT, 5))).build());
        GridSession second = replacement.show(bob, grid(secondBox, BindingMode.EDITABLE));
        tick();
        InventoryView secondView = bob.getOpenInventory();
        assertThat(firstBox.owner()).isEqualTo(secondBox.owner());

        runtime.unload(service);
        runtime.unload(service);

        assertThat(first.result().toCompletableFuture().get(2, TimeUnit.SECONDS))
                .isEqualTo(new UiResult.Closed<Ack>(CloseReason.OWNER_UNLOADED));
        assertThat(firstBox.state()).isEqualTo(ContainerState.ENDED);
        assertThat(secondBox.state()).isEqualTo(ContainerState.EDITABLE);
        assertThat(second.isOpen()).isTrue();
        assertThat(bob.getOpenInventory()).isSameAs(secondView);
        InventoryClickEvent pickup = new InventoryClickEvent(secondView, InventoryType.SlotType.CONTAINER,
                0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(pickup);
        assertThat(pickup.isCancelled()).isTrue();
        assertThat(secondBox.get(0).isEmpty()).isTrue();
        assertThat(bob.getItemOnCursor()).isEqualTo(stack(Material.DIRT, 5));
        tick();
        assertThat(secondView.getTopInventory().isEmpty()).isTrue();
        assertThat(bob.syncScopes).hasSize(1);
    }

    @Test void closeEventsMapReasonsAndCodeCloseKeepsItsChosenReason() throws Exception {
        GridSession session = open(Grid.builder(Text.plain("Close"), 1).build());
        server.getPluginManager().callEvent(new InventoryCloseEvent(player.getOpenInventory(), InventoryCloseEvent.Reason.DISCONNECT));
        tick();
        assertThat(session.result().toCompletableFuture().get(2, TimeUnit.SECONDS))
                .isEqualTo(new UiResult.Closed<Ack>(CloseReason.DISCONNECTED));
        GridSession second = open(Grid.builder(Text.plain("Close"), 1).build());
        second.close();
        tick();
        assertThat(second.result().toCompletableFuture().get(2, TimeUnit.SECONDS))
                .isEqualTo(new UiResult.Closed<Ack>(CloseReason.CLOSED_BY_CODE));
    }

    @Test void anotherViewersCloseDoesNotEndTheOwnersSession() {
        SlotContainer box = container(1, List.of(stack(Material.STONE, 8)));
        GridSession session = open(grid(box, BindingMode.EDITABLE));
        SyncPlayer bob = new SyncPlayer(server, "Bob");
        server.addPlayer(bob);
        bob.openInventory(top());
        server.getPluginManager().callEvent(new InventoryCloseEvent(bob.getOpenInventory(), InventoryCloseEvent.Reason.PLAYER));
        tick();
        assertThat(session.isOpen()).isTrue();
        assertThat(session.result().toCompletableFuture()).isNotDone();
        assertThat(box.get(0)).isEqualTo(stack(Material.STONE, 8));
    }

    @Test void aForeignHolderIsNotTouchedByOurListeners() {
        player.openInventory(server.createInventory(null, 9));
        assertThat(click(0, ClickType.LEFT, InventoryAction.PICKUP_ALL).isCancelled()).isFalse();
        assertThat(drag(false, Map.of(0, stack(Material.DIRT, 1))).isCancelled()).isFalse();
    }

    private InventoryClickEvent click(int raw, ClickType type, InventoryAction action) {
        InventoryClickEvent event = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER, raw, type, action);
        server.getPluginManager().callEvent(event);
        return event;
    }

    private InventoryDragEvent drag(boolean single, Map<Integer, ItemStack> targets) {
        InventoryView view = player.getOpenInventory();
        ItemStack oldCursor = view.getCursor().clone();
        int moved = targets.entrySet().stream().mapToInt(entry -> {
            ItemStack before = view.getInventory(entry.getKey()).getItem(view.convertSlot(entry.getKey()));
            int amount = before == null || before.isEmpty() ? 0 : before.getAmount();
            return Math.max(0, entry.getValue().getAmount() - amount);
        }).sum();
        ItemStack remainder = oldCursor.clone();
        remainder.setAmount(Math.max(0, oldCursor.getAmount() - moved));
        view.setCursor(remainder);
        InventoryDragEvent event = new InventoryDragEvent(view, remainder, oldCursor, single,
                new LinkedHashMap<>(targets));
        server.getPluginManager().callEvent(event);
        if (event.isCancelled()) { view.setCursor(oldCursor); }
        else {
            targets.forEach((raw, item) -> view.getInventory(raw).setItem(view.convertSlot(raw), item));
            view.setCursor(event.getCursor());
        }
        return event;
    }

    static final class CancellationObserver implements Listener {
        final List<Boolean> cancelled = new ArrayList<>();
        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
        public void observe(InventoryClickEvent event) { cancelled.add(event.isCancelled()); }
    }
}
