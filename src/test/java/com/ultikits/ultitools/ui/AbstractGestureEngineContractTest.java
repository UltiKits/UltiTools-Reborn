package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.SimpleFormatter;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** E2-E10, E13, E20, E21 and E25, without a client or an event decoder. */
@Timeout(120)
public abstract class AbstractGestureEngineContractTest extends EditableContractSupport {
    /**
     * Supplies the real container engine and module service, with all scheduled work routed through the
     * fixture's RecordingScheduler. No decoding belongs here: each backend separately proves E1/E2 resync.
     * The same fixture must support multi-viewer redraw and rendered-snapshot gesture dispatch.
     */
    @Override protected abstract AbstractUiServiceContractTest.Fixture createFixture();

    private static List<Gesture> gestures() {
        List<Gesture> gestures = new ArrayList<>(List.of(new Gesture.Pickup(SLOT, false), new Gesture.Pickup(SLOT, true),
                new Gesture.Place(SLOT, false), new Gesture.Place(SLOT, true), new Gesture.SwapWithCursor(SLOT),
                new Gesture.QuickMoveOut(SLOT), new Gesture.QuickMoveIn(10),
                new Gesture.Spread(Set.of(SLOT, GridSlot.ofIndex(1)), false),
                new Gesture.Spread(Set.of(SLOT, GridSlot.ofIndex(1)), true)));
        for (int hotbar = 0; hotbar < 9; hotbar++) {
            gestures.add(new Gesture.HotbarSwap(SLOT, hotbar));
        }
        return gestures;
    }

    @Test void everyGesture_everyState_conservesItems() {
        int combinations = 0;
        for (ContainerKind kind : ContainerKind.values()) {
            for (BindingMode mode : BindingMode.values()) {
                for (int policy = 0; policy < 3; policy++) {
                    for (int held = 0; held < 4; held++) {
                        for (int cursor = 0; cursor < 3; cursor++) {
                            for (int hotbar = 0; hotbar < 3; hotbar++) {
                                for (Gesture gesture : gestures()) {
                                    checkCombination(kind, mode, policy, held, cursor, hotbar, gesture);
                                    combinations++;
                                }
                            }
                        }
                    }
                }
            }
        }
        assertThat(combinations).isEqualTo(7776);
        System.out.println("Editable exhaustive conservation/atomicity combinations: " + combinations);
    }
    private void checkCombination(ContainerKind kind, BindingMode mode, int policy, int held, int cursor, int hotbar, Gesture gesture) {
        TransferPolicy decision = ignored -> switch (policy) {
            case 0 -> TransferDecision.allow();
            case 1 -> TransferDecision.allowAtMost(1);
            default -> TransferDecision.refuse();
        };
        ItemStack slot = switch (held) {
            case 0 -> ItemStack.empty();
            case 1 -> stone(5);
            case 2 -> dirt(5);
            default -> stone(64);
        };
        ContainerSpec.Builder builder = kind == ContainerKind.STORAGE ? storage(2) : ContainerSpec.template(2);
        SlotContainer container = service.container(builder.policy(decision).baseline(List.of(slot, ItemStack.empty())).build());
        InMemoryViewerItems side = side();
        side.cursor(stateItem(cursor));
        int pressed = gesture instanceof Gesture.HotbarSwap swap ? swap.hotbar() : 4;
        side.slot(pressed, stateItem(hotbar));
        side.slot(10, stone(5));
        Grid grid = Grid.builder(TITLE, 1).bindRange(0, 0, 2, container, 0, mode).build();
        List<ItemStack> originalContainer = container.contents();
        List<ItemStack> originalSide = playerItems(side);
        List<ItemStack> all = new ArrayList<>(originalContainer);
        all.addAll(originalSide);
        boolean moved = engine.apply(grid, viewer, side, gesture);
        if (kind == ContainerKind.STORAGE) {
            List<ItemStack> after = new ArrayList<>(container.contents());
            after.addAll(playerItems(side));
            assertThat(totals(after)).as("conservation: %s/%s/%s/%s/%s/%s/%s", kind, mode, policy, held, cursor, hotbar, gesture)
                    .isEqualTo(totals(all));
        } else {
            assertThat(playerItems(side)).as("template: %s", gesture).isEqualTo(originalSide);
        }
        if (!moved || policy == 2 || mode == BindingMode.VIEW || partialSwap(gesture, slot, originalSide, pressed, policy)) {
            assertThat(container.contents()).as("atomicity: %s", gesture).isEqualTo(originalContainer);
            assertThat(playerItems(side)).isEqualTo(originalSide);
        }
    }
    private static ItemStack stateItem(int state) { return state == 0 ? ItemStack.empty() : state == 1 ? stone(3) : dirt(3); }
    private static boolean partialSwap(Gesture gesture, ItemStack held, List<ItemStack> side, int hotbar, int policy) {
        if (policy != 1 || held.isEmpty()) {
            return false;
        }
        return switch (gesture) {
            case Gesture.HotbarSwap ignored -> !side.get(hotbar).isEmpty();
            case Gesture.SwapWithCursor ignored -> !side.getLast().isEmpty();
            default -> false;
        };
    }
    @Test void hotbarSwap_emptySlot_putsInHotbarStack() {
        SlotContainer container = service.container(storage(1).build());
        InMemoryViewerItems side = side();
        side.slot(7, stone(8));
        side.cursor(dirt(2));
        assertThat(apply(container, side, new Gesture.HotbarSwap(SLOT, 7))).isTrue();
        assertThat(container.get(0)).isEqualTo(stone(8));
        assertThat(side.slot(7).isEmpty()).isTrue();
        assertThat(side.cursor()).isEqualTo(dirt(2));
        assertThat(quantity(container.changes().putInBy().get(viewer.getUniqueId()), Material.STONE)).isEqualTo(8);
    }
    @Test void hotbarSwap_emptyHotbar_takesOutIntoPressedSlotOnly() {
        for (int pressed = 0; pressed < 9; pressed++) {
            SlotContainer container = service.container(storage(1).baseline(List.of(stone(8))).build());
            InMemoryViewerItems side = side();
            assertThat(apply(container, side, new Gesture.HotbarSwap(SLOT, pressed))).isTrue();
            assertThat(side.slot(pressed)).isEqualTo(stone(8));
            assertThat(inventory(side).stream().filter(item -> !item.isEmpty()).count()).isOne();
            assertThat(container.get(0).isEmpty()).isTrue();
        }
    }
    @Test void hotbarSwap_bothPresent_swapsOrMovesNothing() {
        for (boolean allow : List.of(true, false)) {
            SlotContainer container = service.container(storage(1).baseline(List.of(stone(8)))
                    .policy(request -> request.kind() == TransferKind.PUT_IN && !allow ? TransferDecision.refuse() : TransferDecision.allow()).build());
            InMemoryViewerItems side = side();
            side.slot(6, dirt(3));
            assertThat(apply(container, side, new Gesture.HotbarSwap(SLOT, 6))).isEqualTo(allow);
            assertThat(container.get(0)).isEqualTo(allow ? dirt(3) : stone(8));
            assertThat(side.slot(6)).isEqualTo(allow ? stone(8) : dirt(3));
        }
    }
    @Test void hotbarSwap_atMost_movesPartAndKeepsRest() {
        SlotContainer container = service.container(storage(1).policy(request -> TransferDecision.allowAtMost(2)).build());
        InMemoryViewerItems side = side();
        side.slot(3, stone(8));
        assertThat(apply(container, side, new Gesture.HotbarSwap(SLOT, 3))).isTrue();
        assertThat(container.get(0)).isEqualTo(stone(2));
        assertThat(side.slot(3)).isEqualTo(stone(6));
        side.slot(3, ItemStack.empty());
        assertThat(apply(container, side, new Gesture.HotbarSwap(SLOT, 3))).isTrue();
        assertThat(container.get(0).isEmpty()).isTrue();
        assertThat(side.slot(3)).isEqualTo(stone(2));
        container.load(List.of(stone(8))); side.slot(3, ItemStack.empty());
        assertThat(apply(container, side, new Gesture.HotbarSwap(SLOT, 3))).isTrue();
        assertThat(container.get(0)).isEqualTo(stone(6));
        assertThat(side.slot(3)).isEqualTo(stone(2));
    }
    @Test void hotbarSwap_viewBindingOrIcon_movesNothing() {
        AtomicInteger calls = new AtomicInteger();
        SlotContainer container = service.container(storage(1).baseline(List.of(stone(8))).policy(request -> {
            calls.incrementAndGet(); return TransferDecision.allow();
        }).build());
        InMemoryViewerItems side = side();
        side.slot(4, dirt(3));
        Grid view = grid(container, BindingMode.VIEW);
        Grid icon = Grid.builder(TITLE, 1).set(SLOT, GridItem.of(ItemSpec.builder(Key.key("minecraft", "stone")).build())).build();
        assertThat(engine.apply(view, viewer, side, new Gesture.HotbarSwap(SLOT, 4))).isFalse();
        assertThat(engine.apply(icon, viewer, side, new Gesture.HotbarSwap(SLOT, 4))).isFalse();
        assertThat(container.get(0)).isEqualTo(stone(8));
        assertThat(side.slot(4)).isEqualTo(dirt(3));
        assertThat(calls).hasValue(0);
    }
    @Test void template_hotbarSwap_leavesHotbarUnchanged() {
        SlotContainer container = service.container(ContainerSpec.template(1).baseline(List.of(stone(8))).build());
        InMemoryViewerItems side = side();
        side.slot(8, dirt(3));
        assertThat(apply(container, side, new Gesture.HotbarSwap(SLOT, 8))).isTrue();
        assertThat(container.get(0)).isEqualTo(dirt(3));
        assertThat(side.slot(8)).isEqualTo(dirt(3));
        side.slot(8, ItemStack.empty());
        assertThat(apply(container, side, new Gesture.HotbarSwap(SLOT, 8))).isTrue();
        assertThat(container.get(0).isEmpty()).isTrue();
        assertThat(side.slot(8).isEmpty()).isTrue();
    }
    @Test void viewBinding_anyGesture_movesNothingAndSkipsPolicy() { assertRefused(null, BindingMode.VIEW); }
    @Test void readOnlyContainer_anyGesture_movesNothing() { assertRefused(ContainerState.READ_ONLY, BindingMode.EDITABLE); }
    @Test void committingContainer_anyGesture_movesNothing() { assertRefused(ContainerState.COMMITTING, BindingMode.EDITABLE); }
    @Test void failedOrEndedContainer_anyGesture_movesNothing() {
        assertRefused(ContainerState.FAILED, BindingMode.EDITABLE);
        assertRefused(ContainerState.ENDED, BindingMode.EDITABLE);
    }
    private void assertRefused(ContainerState state, BindingMode mode) {
        for (Gesture gesture : gestures()) {
            AtomicInteger calls = new AtomicInteger();
            CompletableFuture<StoreAnswer> answer = new CompletableFuture<>();
            SlotContainer container = service.container(storage(2).initial(viewer.getUniqueId(), List.of(stone(8), stone(2)))
                    .store(snapshot -> answer).policy(request -> { calls.incrementAndGet(); return TransferDecision.allow(); }).build());
            Grid grid = Grid.builder(TITLE, 1).bindRange(0, 0, 2, container, 0, mode).build();
            if (state == ContainerState.READ_ONLY) { container.setReadOnly(true); }
            if (state == ContainerState.COMMITTING || state == ContainerState.FAILED) { container.commit(); }
            if (state == ContainerState.FAILED) { answer.complete(StoreAnswer.unknown(null)); settle(); }
            if (state == ContainerState.ENDED) { container.drain(); }
            InMemoryViewerItems side = side();
            side.cursor(dirt(3));
            side.slot(10, stone(3));
            List<ItemStack> before = container.contents();
            List<ItemStack> playerBefore = playerItems(side);
            assertThat(engine.apply(grid, viewer, side, gesture)).isFalse();
            assertThat(container.contents()).isEqualTo(before);
            assertThat(playerItems(side)).isEqualTo(playerBefore);
            assertThat(calls).hasValue(0);
        }
    }
    @Test void swap_putInRefused_movesNothing() {
        SlotContainer container = service.container(storage(1).baseline(List.of(stone(8)))
                .policy(request -> request.kind() == TransferKind.PUT_IN ? TransferDecision.refuse() : TransferDecision.allow()).build());
        InMemoryViewerItems side = side();
        side.cursor(dirt(3));
        assertThat(apply(container, side, new Gesture.SwapWithCursor(SLOT))).isFalse();
        assertThat(container.get(0)).isEqualTo(stone(8));
        assertThat(side.cursor()).isEqualTo(dirt(3));
        assertThat(container.changes().isEmpty()).isTrue();
    }
    @Test void policyThrows_countsAsRefuse() {
        IllegalStateException failure = new IllegalStateException("policy");
        SlotContainer container = service.container(storage(1).baseline(List.of(stone(8))).policy(request -> { throw failure; }).build());
        assertThat(apply(container, side(), new Gesture.Pickup(SLOT, false))).isFalse();
        assertThat(fixture.warnings()).anySatisfy(record -> {
            assertThat(new SimpleFormatter().formatMessage(record)).contains(service.owner());
            assertThat(record.getThrown()).isSameAs(failure);
        });
    }
    @Test void policy_askedOncePerTransfer() {
        List<TransferRequest> requests = new ArrayList<>();
        SlotContainer container = service.container(storage(1).baseline(List.of(stone(8))).policy(request -> {
            assertThat(RecordingScheduler.inEntityTask(viewer)).isTrue();
            assertThat(request.viewer()).isSameAs(viewer);
            request.item().setAmount(1);
            requests.add(request);
            return TransferDecision.allow();
        }).build());
        InMemoryViewerItems side = side();
        side.cursor(dirt(3));
        fixture.scheduler().entity(viewer).run(() -> apply(container, side, new Gesture.SwapWithCursor(SLOT)));
        settle();
        assertThat(requests).extracting(TransferRequest::kind).containsExactly(TransferKind.TAKE_OUT, TransferKind.PUT_IN);
        assertThat(requests).extracting(TransferRequest::amount).containsExactly(8, 3);
        assertThat(container.get(0)).isEqualTo(dirt(3));
        assertThat(side.cursor()).isEqualTo(stone(8));
    }
    @Test void refusalMessage_isSentExactlyOnce() {
        SlotContainer container = service.container(storage(1).baseline(List.of(stone(8)))
                .policy(request -> TransferDecision.refuse(Text.plain("Not allowed"))).build());
        assertThat(apply(container, side(), new Gesture.Pickup(SLOT, false))).isFalse();
        viewer.assertSaid("Not allowed");
        viewer.assertNoMoreSaid();
    }
    @Test void quickMoveOut_fullInventory_keepsRestInContainer() {
        SlotContainer container = service.container(storage(1).baseline(List.of(stone(8))).build());
        InMemoryViewerItems side = side();
        for (int i = 0; i < side.inventorySize(); i++) { side.slot(i, dirt(64)); }
        side.slot(17, stone(62));
        assertThat(apply(container, side, new Gesture.QuickMoveOut(SLOT))).isTrue();
        assertThat(container.get(0)).isEqualTo(stone(6));
        assertThat(side.slot(17)).isEqualTo(stone(64));
        assertThat(apply(container, side, new Gesture.QuickMoveOut(SLOT))).isFalse();
        assertThat(container.get(0)).isEqualTo(stone(6));
    }
    @Test void quickMoveIn_mergesThenEmpty_inIndexOrder_visibleEditableOnly() {
        List<TransferRequest> asked = new ArrayList<>();
        SlotContainer container = service.container(storage(5).baseline(List.of(ItemStack.empty(), stone(63), stone(63),
                ItemStack.empty(), ItemStack.empty())).policy(request -> { asked.add(request); return TransferDecision.allow(); }).build());
        Grid grid = Grid.builder(TITLE, 1).bind(GridSlot.ofIndex(0), container, 3, BindingMode.EDITABLE)
                .bind(GridSlot.ofIndex(1), container, 1, BindingMode.EDITABLE)
                .bind(GridSlot.ofIndex(2), container, 0, BindingMode.EDITABLE)
                .bind(GridSlot.ofIndex(3), container, 2, BindingMode.VIEW).build();
        InMemoryViewerItems side = side();
        side.slot(10, stone(64));
        assertThat(engine.apply(grid, viewer, side, new Gesture.QuickMoveIn(10))).isTrue();
        assertThat(asked).extracting(TransferRequest::index).containsExactly(1, 0);
        assertThat(container.get(1)).isEqualTo(stone(64));
        assertThat(container.get(0)).isEqualTo(stone(63));
        assertThat(container.get(2)).isEqualTo(stone(63));
        assertThat(container.get(3).isEmpty()).isTrue();
        assertThat(container.get(4).isEmpty()).isTrue();
        assertThat(side.slot(10).isEmpty()).isTrue();
    }
    @Test void spread_touchesViewBinding_movesNothing() {
        SlotContainer container = service.container(storage(2).build());
        Grid grid = Grid.builder(TITLE, 1).bind(SLOT, container, 0, BindingMode.EDITABLE)
                .bind(GridSlot.ofIndex(1), container, 1, BindingMode.VIEW).build();
        InMemoryViewerItems side = side();
        side.cursor(stone(7));
        assertThat(engine.apply(grid, viewer, side, new Gesture.Spread(grid.bindings().keySet(), false))).isFalse();
        assertThat(container.contents()).allSatisfy(item -> assertThat(item.isEmpty()).isTrue());
        assertThat(side.cursor()).isEqualTo(stone(7));
    }
    @Test void spread_even_leavesRemainderOnCursor() {
        SlotContainer container = service.container(storage(2).build());
        Grid grid = Grid.builder(TITLE, 1).bindRange(0, 0, 2, container, 0, BindingMode.EDITABLE).build();
        InMemoryViewerItems side = side();
        side.cursor(stone(7));
        assertThat(engine.apply(grid, viewer, side, new Gesture.Spread(grid.bindings().keySet(), false))).isTrue();
        assertThat(container.contents()).containsExactly(stone(3), stone(3));
        assertThat(side.cursor()).isEqualTo(stone(1));
    }
    @Test void template_place_leavesViewerItemsUnchanged() {
        SlotContainer container = service.container(ContainerSpec.template(1).build());
        InMemoryViewerItems side = side();
        side.cursor(stone(8));
        assertThat(apply(container, side, new Gesture.Place(SLOT, false))).isTrue();
        assertThat(container.get(0)).isEqualTo(stone(8));
        assertThat(side.cursor()).isEqualTo(stone(8));
    }
    @Test void template_pickup_givesNothing() {
        SlotContainer container = service.container(ContainerSpec.template(1).baseline(List.of(stone(8))).build());
        InMemoryViewerItems side = side();
        assertThat(apply(container, side, new Gesture.Pickup(SLOT, true))).isTrue();
        assertThat(container.get(0).isEmpty()).isTrue();
        assertThat(side.cursor().isEmpty()).isTrue();
        assertThat(inventory(side)).allSatisfy(item -> assertThat(item.isEmpty()).isTrue());
    }
    @Test void onChange_runsOnceAfterEachTransfer() {
        List<ContainerChange> changes = new ArrayList<>();
        AtomicReference<SlotContainer> reference = new AtomicReference<>();
        InMemoryViewerItems side = side();
        side.cursor(dirt(3));
        SlotContainer container = service.container(storage(1).baseline(List.of(stone(8))).onChange(change -> {
            changes.add(change);
            assertThat(reference.get().get(0)).isEqualTo(dirt(3));
            assertThat(side.cursor()).isEqualTo(stone(8));
            change.item().setAmount(1);
        }).build());
        reference.set(container);
        assertThat(apply(container, side, new Gesture.SwapWithCursor(SLOT))).isTrue();
        assertThat(changes).extracting(ContainerChange::kind).containsExactly(TransferKind.TAKE_OUT, TransferKind.PUT_IN);
        assertThat(changes).extracting(ContainerChange::amount).containsExactly(8, 3);
    }
    @Test void transfer_redrawsEveryViewerSession() {
        AbstractUiServiceContractTest.ProbeBackend backend = new AbstractUiServiceContractTest.ProbeBackend("grid", 0, Grid.class);
        fixture.register(backend);
        SlotContainer container = service.container(storage(1).build());
        PlayerMock other = server.addPlayer();
        GridSession first = service.show(viewer, grid(container));
        GridSession second = service.show(other, grid(container, BindingMode.VIEW));
        settle();
        InMemoryViewerItems side = side();
        side.cursor(stone(8));
        assertThat(fixture.gesture(first, side, new Gesture.Place(SLOT, false))).isTrue();
        fixture.advanceTicks(1);
        assertThat(backend.shown(first, SLOT)).isEqualTo(stone(8));
        assertThat(backend.shown(second, SLOT)).isEqualTo(stone(8));
    }
    @Test void onChangeThrows_isLoggedWithoutUndoingTransfer() {
        IllegalStateException failure = new IllegalStateException("listener");
        SlotContainer container = service.container(storage(1).onChange(change -> { throw failure; }).build());
        InMemoryViewerItems side = side(); side.cursor(stone(8));
        assertThat(apply(container, side, new Gesture.Place(SLOT, false))).isTrue();
        assertThat(container.get(0)).isEqualTo(stone(8));
        assertThat(side.cursor().isEmpty()).isTrue();
        assertThat(fixture.warnings()).anySatisfy(record -> {
            assertThat(new SimpleFormatter().formatMessage(record)).contains(service.owner());
            assertThat(record.getThrown()).isSameAs(failure);
        });
    }
    @Test void ledger_otherContributorsAreReducedInOrder_thenBaseline() {
        PlayerMock first = server.addPlayer();
        PlayerMock second = server.addPlayer();
        SlotContainer container = service.container(storage(1).baseline(List.of(stone(2))).build());
        InMemoryViewerItems firstSide = side(); firstSide.cursor(stone(3));
        InMemoryViewerItems secondSide = side(); secondSide.cursor(stone(3));
        assertThat(engine.apply(grid(container), first, firstSide, new Gesture.Place(SLOT, false))).isTrue();
        assertThat(engine.apply(grid(container), second, secondSide, new Gesture.Place(SLOT, false))).isTrue();
        InMemoryViewerItems taking = side();
        assertThat(apply(container, taking, new Gesture.Pickup(SLOT, true))).isTrue();
        assertThat(container.changes().putInBy()).doesNotContainKey(first.getUniqueId());
        assertThat(container.changes().putInBy().get(second.getUniqueId())).containsExactly(stone(2));
        assertThat(container.changes().takenOut()).isEmpty();
        taking.cursor(ItemStack.empty());
        assertThat(apply(container, taking, new Gesture.Pickup(SLOT, false))).isTrue();
        assertThat(container.changes().putInBy()).isEmpty();
        assertThat(container.changes().takenOut()).containsExactly(stone(2));
    }
    @Test void ledger_takeOutReducesOwnPutInsFirst() {
        PlayerMock other = server.addPlayer();
        SlotContainer container = service.container(storage(1).initial(other.getUniqueId(), List.of(stone(3))).build());
        InMemoryViewerItems side = side();
        side.cursor(stone(5));
        assertThat(apply(container, side, new Gesture.Place(SLOT, false))).isTrue();
        assertThat(apply(container, side, new Gesture.Pickup(SLOT, true))).isTrue();
        assertThat(quantity(container.changes().putInBy().get(viewer.getUniqueId()), Material.STONE)).isOne();
        assertThat(quantity(container.changes().putInBy().get(other.getUniqueId()), Material.STONE)).isEqualTo(3);
        assertThat(container.changes().takenOut()).isEmpty();
    }
}
