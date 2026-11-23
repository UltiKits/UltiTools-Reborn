package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
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
                    Setting setting = new Setting(kind, mode, policy);
                    combinations += cursorFamily(setting);
                    combinations += quickOutFamily(setting);
                    combinations += hotbarFamily(setting);
                    combinations += quickInFamily(setting);
                    combinations += spreadFamily(setting);
                }
            }
        }
        assertThat(combinations).isEqualTo(7392);
    }
    private record Setting(ContainerKind kind, BindingMode mode, int policy) { }
    private static List<ItemStack> slotStates() {
        return List.of(ItemStack.empty(), stone(5), stone(62), stone(64), dirt(5), namedStone(5), pearl(15), sword(1));
    }
    private static List<ItemStack> cursorStates() {
        return List.of(ItemStack.empty(), stone(3), stone(64), dirt(3), namedStone(3), pearl(3));
    }
    private int cursorFamily(Setting setting) {
        int checked = 0;
        List<Gesture> cursorGestures = List.of(new Gesture.Pickup(SLOT, false), new Gesture.Pickup(SLOT, true),
                new Gesture.Place(SLOT, false), new Gesture.Place(SLOT, true), new Gesture.SwapWithCursor(SLOT));
        for (ItemStack held : slotStates()) {
            for (ItemStack cursor : cursorStates()) {
                for (Gesture gesture : cursorGestures) {
                    InMemoryViewerItems side = side(); side.cursor(cursor);
                    checkCombination(setting, List.of(held, ItemStack.empty()), side, gesture);
                    checked++;
                }
            }
        }
        return checked;
    }
    private int quickOutFamily(Setting setting) {
        int checked = 0;
        for (ItemStack held : slotStates()) {
            for (int fill = 0; fill < 3; fill++) {
                InMemoryViewerItems side = filledInventory(fill);
                checkCombination(setting, List.of(held, ItemStack.empty()), side, new Gesture.QuickMoveOut(SLOT));
                checked++;
            }
        }
        return checked;
    }
    private InMemoryViewerItems filledInventory(int fill) {
        InMemoryViewerItems side = side();
        if (fill != 0) {
            for (int i = 0; i < 36; i++) { side.slot(i, dirt(64)); }
        }
        if (fill == 1) { side.slot(35, stone(60)); }
        return side;
    }
    private int hotbarFamily(Setting setting) {
        int checked = 0;
        for (ItemStack held : slotStates()) {
            for (ItemStack source : cursorStates()) {
                for (int pressed : List.of(0, 8)) {
                    InMemoryViewerItems side = side(); side.slot(pressed, source);
                    checkCombination(setting, List.of(held, ItemStack.empty()), side, new Gesture.HotbarSwap(SLOT, pressed));
                    checked++;
                }
            }
        }
        return checked;
    }
    private int quickInFamily(Setting setting) {
        int checked = 0;
        List<ItemStack> sources = List.of(stone(5), stone(64), dirt(5), namedStone(5), pearl(16), sword(1));
        for (ItemStack source : sources) {
            for (ItemStack held : slotStates()) {
                for (ItemStack second : List.of(ItemStack.empty(), stone(62))) {
                    InMemoryViewerItems side = side(); side.slot(10, source);
                    checkCombination(setting, List.of(held, second), side, new Gesture.QuickMoveIn(10));
                    checked++;
                }
            }
        }
        return checked;
    }
    private int spreadFamily(Setting setting) {
        int checked = 0;
        for (ItemStack cursor : cursorStates().subList(1, 6)) {
            for (ItemStack held : slotStates()) {
                for (ItemStack second : List.of(ItemStack.empty(), stone(62))) {
                    for (boolean one : List.of(false, true)) {
                        InMemoryViewerItems side = side(); side.cursor(cursor);
                        checkCombination(setting, List.of(held, second), side, new Gesture.Spread(Set.of(SLOT, GridSlot.ofIndex(1)), one));
                        checked++;
                    }
                }
            }
        }
        return checked;
    }
    private void checkCombination(Setting setting, List<ItemStack> initial, InMemoryViewerItems side, Gesture gesture) {
        ContainerSpec.Builder builder = setting.kind() == ContainerKind.STORAGE ? storage(2) : ContainerSpec.template(2);
        SlotContainer container = service.container(builder.policy(policyFor(setting.policy())).baseline(initial).build());
        Grid grid = Grid.builder(TITLE, 1).bindRange(0, 0, 2, container, 0, setting.mode()).build();
        List<ItemStack> before = container.contents();
        List<ItemStack> playerBefore = playerItems(side);
        boolean moved = engine.apply(grid, viewer, side, gesture);
        List<ItemStack> after = container.contents();
        List<ItemStack> playerAfter = playerItems(side);
        String context = setting + "/" + gesture + "/" + before + "/" + playerBefore;
        checkConservation(setting.kind(), before, playerBefore, after, playerAfter, context);
        checkStackLimits(after, context);
        checkStackLimits(playerAfter, context);
        checkRefusal(setting, moved, before, playerBefore, after, playerAfter, context);
        assertThat(moved).as("O5 return value: %s", context)
                .isEqualTo(!before.equals(after) || !playerBefore.equals(playerAfter));
        checkAtMost(setting.policy(), before, after, gesture, context);
        checkSwap(setting.kind(), before, playerBefore, after, playerAfter, gesture, context);
        checkRequiredMove(setting, before, playerBefore, gesture, moved, context);
    }
    private static void checkConservation(ContainerKind kind, List<ItemStack> before, List<ItemStack> playerBefore,
            List<ItemStack> after, List<ItemStack> playerAfter, String context) {
        if (kind == ContainerKind.STORAGE) {
            List<ItemStack> allBefore = new ArrayList<>(before); allBefore.addAll(playerBefore);
            List<ItemStack> allAfter = new ArrayList<>(after); allAfter.addAll(playerAfter);
            assertThat(totals(allAfter)).as("O1 conservation: %s", context).isEqualTo(totals(allBefore));
        } else {
            assertThat(playerAfter).as("O2 template real items: %s", context).isEqualTo(playerBefore);
        }
    }
    private static void checkStackLimits(List<ItemStack> items, String context) {
        for (ItemStack item : items) {
            assertThat(item.getAmount()).as("O3 max stack: %s / %s", context, item).isLessThanOrEqualTo(item.getMaxStackSize());
        }
    }
    private static void checkRefusal(Setting setting, boolean moved, List<ItemStack> before, List<ItemStack> playerBefore,
            List<ItemStack> after, List<ItemStack> playerAfter, String context) {
        if (setting.policy() == 2 || setting.mode() == BindingMode.VIEW) {
            assertThat(moved).as("O4 refused: %s", context).isFalse();
            assertThat(after).as("O4 container: %s", context).isEqualTo(before);
            assertThat(playerAfter).as("O4 viewer: %s", context).isEqualTo(playerBefore);
        }
    }
    private static void checkAtMost(int policy, List<ItemStack> before, List<ItemStack> after, Gesture gesture, String context) {
        if (policy != 1) { return; }
        Map<ItemStack, Integer> oldTotals = totals(before);
        Map<ItemStack, Integer> newTotals = totals(after);
        Set<ItemStack> types = new LinkedHashSet<>(oldTotals.keySet()); types.addAll(newTotals.keySet());
        int delta = types.stream().mapToInt(type -> Math.abs(oldTotals.getOrDefault(type, 0) - newTotals.getOrDefault(type, 0))).sum();
        int limit = switch (gesture) {
            case Gesture.Pickup ignored -> 1;
            case Gesture.Place ignored -> 1;
            case Gesture.QuickMoveOut ignored -> 1;
            default -> 2;
        };
        assertThat(delta).as("O6 per-transfer bound: %s", context).isLessThanOrEqualTo(limit);
    }
    private static void checkSwap(ContainerKind kind, List<ItemStack> before, List<ItemStack> playerBefore,
            List<ItemStack> after, List<ItemStack> playerAfter, Gesture gesture, String context) {
        int sourceIndex = switch (gesture) {
            case Gesture.SwapWithCursor ignored -> 36;
            case Gesture.HotbarSwap swap -> swap.hotbar();
            default -> -1;
        };
        if (sourceIndex < 0) { return; }
        ItemStack held = before.getFirst();
        ItemStack source = playerBefore.get(sourceIndex);
        if (held.isEmpty() || source.isEmpty() || held.isSimilar(source)) { return; }
        if (before.equals(after) && playerBefore.equals(playerAfter)) { return; }
        assertThat(after).as("O7 full container swap: %s", context).containsExactly(source, before.get(1));
        List<ItemStack> swappedSide = new ArrayList<>(playerBefore);
        if (kind == ContainerKind.STORAGE) { swappedSide.set(sourceIndex, held); }
        assertThat(playerAfter).as("O7 full viewer swap: %s", context).isEqualTo(swappedSide);
    }
    private static void checkRequiredMove(Setting setting, List<ItemStack> before, List<ItemStack> playerBefore,
            Gesture gesture, boolean moved, String context) {
        if (setting.kind() != ContainerKind.STORAGE || setting.mode() != BindingMode.EDITABLE || setting.policy() != 0) { return; }
        if (shouldMove(before, playerBefore, gesture)) {
            assertThat(moved).as("O8 required move: %s", context).isTrue();
        }
    }
    private static boolean shouldMove(List<ItemStack> before, List<ItemStack> playerBefore, Gesture gesture) {
        ItemStack held = before.getFirst();
        ItemStack cursor = playerBefore.getLast();
        return switch (gesture) {
            case Gesture.Pickup ignored -> !held.isEmpty() && cursor.isEmpty();
            case Gesture.Place ignored -> accepts(held, cursor);
            case Gesture.SwapWithCursor ignored -> !held.isEmpty() && !cursor.isEmpty() && !held.isSimilar(cursor);
            case Gesture.HotbarSwap swap -> !held.equals(playerBefore.get(swap.hotbar()));
            case Gesture.QuickMoveOut ignored -> playerBefore.subList(0, 36).stream().anyMatch(target -> accepts(target, held));
            case Gesture.QuickMoveIn move -> before.stream().anyMatch(target -> accepts(target, playerBefore.get(move.playerSlot())));
            case Gesture.Spread spread -> canSpread(before, cursor, spread.one());
        };
    }
    private static boolean canSpread(List<ItemStack> before, ItemStack cursor, boolean one) {
        int each = one ? 1 : cursor.getAmount() / 2;
        return each >= 1 && before.stream().anyMatch(target -> accepts(target, cursor));
    }
    private static boolean accepts(ItemStack target, ItemStack source) {
        if (source.isEmpty()) { return false; }
        return target.isEmpty() || target.isSimilar(source) && target.getAmount() < source.getMaxStackSize();
    }
    private static TransferPolicy policyFor(int policy) {
        return ignored -> switch (policy) {
            case 0 -> TransferDecision.allow();
            case 1 -> TransferDecision.allowAtMost(1);
            default -> TransferDecision.refuse();
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
            SlotContainer container = service.container(committed(2).initial(viewer.getUniqueId(), List.of(stone(8), stone(2)))
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
    private static int total(List<ItemStack> items) {
        return items.stream().mapToInt(ItemStack::getAmount).sum();
    }
    private TransferPolicy capacityPolicy(AtomicReference<SlotContainer> container) {
        return request -> request.kind() == TransferKind.PUT_IN
                ? TransferDecision.allowAtMost(Math.max(0, 10 - total(container.get().contents()))) : TransferDecision.allow();
    }
    @Test void quickMoveIn_capacityPolicy_seesEarlierTransfersOfSameGesture() {
        AtomicReference<SlotContainer> reference = new AtomicReference<>();
        SlotContainer container = service.container(storage(3).policy(capacityPolicy(reference)).build());
        reference.set(container);
        InMemoryViewerItems side = side();
        side.slot(10, stone(64));
        Grid grid = Grid.builder(TITLE, 1).bindRange(0, 0, 3, container, 0, BindingMode.EDITABLE).build();
        assertThat(engine.apply(grid, viewer, side, new Gesture.QuickMoveIn(10))).isTrue();
        assertThat(total(container.contents())).isEqualTo(10);
        assertThat(side.slot(10)).isEqualTo(stone(54));
    }
    @Test void spread_capacityPolicy_seesEarlierTransfersOfSameGesture() {
        AtomicReference<SlotContainer> reference = new AtomicReference<>();
        SlotContainer container = service.container(storage(3).policy(capacityPolicy(reference)).build());
        reference.set(container);
        InMemoryViewerItems side = side();
        side.cursor(stone(64));
        Grid grid = Grid.builder(TITLE, 1).bindRange(0, 0, 3, container, 0, BindingMode.EDITABLE).build();
        assertThat(engine.apply(grid, viewer, side, new Gesture.Spread(grid.bindings().keySet(), false))).isTrue();
        assertThat(total(container.contents())).isEqualTo(10);
        assertThat(side.cursor()).isEqualTo(stone(54));
    }
    @Test void swap_bothHalvesSeeStateBeforeGesture() {
        AtomicReference<SlotContainer> reference = new AtomicReference<>();
        List<ItemStack> seen = new ArrayList<>();
        List<TransferKind> order = new ArrayList<>();
        SlotContainer container = service.container(storage(1).baseline(List.of(stone(5))).policy(request -> {
            seen.add(reference.get().get(0)); order.add(request.kind()); return TransferDecision.allow();
        }).build());
        reference.set(container);
        InMemoryViewerItems side = side(); side.cursor(dirt(3));
        assertThat(apply(container, side, new Gesture.SwapWithCursor(SLOT))).isTrue();
        assertThat(seen).containsExactly(stone(5), stone(5));
        assertThat(order).containsExactly(TransferKind.TAKE_OUT, TransferKind.PUT_IN);
        assertThat(container.get(0)).isEqualTo(dirt(3));
        assertThat(side.cursor()).isEqualTo(stone(5));
    }
    @Test void refusalMessage_onMultiSlotGesture_isSentOncePerDistinctText() {
        SlotContainer container = service.container(storage(3).policy(request -> TransferDecision.refuse(Text.plain("Full"))).build());
        InMemoryViewerItems side = side(); side.slot(10, stone(64));
        Grid grid = Grid.builder(TITLE, 1).bindRange(0, 0, 3, container, 0, BindingMode.EDITABLE).build();
        assertThat(engine.apply(grid, viewer, side, new Gesture.QuickMoveIn(10))).isFalse();
        viewer.assertSaid("Full"); viewer.assertNoMoreSaid();
    }
    @Test void onChange_runsAfterWholeGesture() {
        AtomicReference<SlotContainer> reference = new AtomicReference<>();
        List<Integer> seen = new ArrayList<>();
        SlotContainer container = service.container(storage(2).onChange(change -> seen.add(total(reference.get().contents()))).build());
        reference.set(container);
        InMemoryViewerItems side = side(); side.cursor(stone(10));
        Grid grid = Grid.builder(TITLE, 1).bindRange(0, 0, 2, container, 0, BindingMode.EDITABLE).build();
        assertThat(engine.apply(grid, viewer, side, new Gesture.Spread(grid.bindings().keySet(), false))).isTrue();
        assertThat(seen).containsExactly(10, 10);
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
    private Grid pair(SlotContainer container) {
        return Grid.builder(TITLE, 1).bindRange(0, 0, 2, container, 0, BindingMode.EDITABLE).build();
    }
    @Test void place_partialRoom_placesWhatFitsAndKeepsRestOnCursor() {
        SlotContainer container = service.container(storage(1).baseline(List.of(stone(62))).build());
        InMemoryViewerItems side = side(); side.cursor(stone(3));
        assertThat(apply(container, side, new Gesture.Place(SLOT, false))).isTrue();
        assertThat(container.get(0)).isEqualTo(stone(64));
        assertThat(side.cursor()).isEqualTo(stone(1));
    }
    @Test void place_respectsItemMaxStack() {
        SlotContainer container = service.container(storage(1).baseline(List.of(pearl(15))).build());
        InMemoryViewerItems side = side(); side.cursor(pearl(3));
        assertThat(apply(container, side, new Gesture.Place(SLOT, false))).isTrue();
        assertThat(container.get(0)).isEqualTo(pearl(16));
        assertThat(side.cursor()).isEqualTo(pearl(2));
        container.load(List.of(sword(1))); side.cursor(sword(1));
        assertThat(apply(container, side, new Gesture.Place(SLOT, false))).isFalse();
        assertThat(container.get(0)).isEqualTo(sword(1));
        assertThat(side.cursor()).isEqualTo(sword(1));
    }
    @Test void place_similarTypeDifferentMeta_isNotMerged() {
        SlotContainer container = service.container(storage(1).baseline(List.of(namedStone(5))).build());
        InMemoryViewerItems side = side(); side.cursor(stone(3));
        assertThat(apply(container, side, new Gesture.Place(SLOT, false))).isFalse();
        assertThat(container.get(0)).isEqualTo(namedStone(5));
        assertThat(side.cursor()).isEqualTo(stone(3));
        assertThat(apply(container, side, new Gesture.SwapWithCursor(SLOT))).isTrue();
        assertThat(container.get(0)).isEqualTo(stone(3));
        assertThat(side.cursor()).isEqualTo(namedStone(5));
    }
    @Test void pickup_halfOfOddStack_takesLargerHalf() {
        SlotContainer container = service.container(storage(1).baseline(List.of(stone(5))).build());
        InMemoryViewerItems side = side();
        assertThat(apply(container, side, new Gesture.Pickup(SLOT, true))).isTrue();
        assertThat(side.cursor()).isEqualTo(stone(3));
        assertThat(container.get(0)).isEqualTo(stone(2));
    }
    @Test void quickMoveIn_overflow_restStaysWithViewer() {
        SlotContainer container = service.container(storage(2).baseline(List.of(stone(62), stone(63))).build());
        InMemoryViewerItems side = side(); side.slot(10, stone(64));
        assertThat(engine.apply(pair(container), viewer, side, new Gesture.QuickMoveIn(10))).isTrue();
        assertThat(container.contents()).containsExactly(stone(64), stone(64));
        assertThat(side.slot(10)).isEqualTo(stone(61));
    }
    @Test void quickMoveIn_respectsItemMaxStack() {
        SlotContainer container = service.container(storage(2).build());
        InMemoryViewerItems side = side(); side.slot(10, pearl(16));
        assertThat(engine.apply(pair(container), viewer, side, new Gesture.QuickMoveIn(10))).isTrue();
        assertThat(container.contents()).containsExactly(pearl(16), ItemStack.empty());
        assertThat(side.slot(10).isEmpty()).isTrue();
        container.load(List.of(pearl(15), ItemStack.empty())); side.slot(10, pearl(16));
        assertThat(engine.apply(pair(container), viewer, side, new Gesture.QuickMoveIn(10))).isTrue();
        assertThat(container.contents()).containsExactly(pearl(16), pearl(15));
        assertThat(side.slot(10).isEmpty()).isTrue();
    }
    @Test void spread_partialRoom_keepsRemainderOnCursor() {
        SlotContainer container = service.container(storage(2).baseline(List.of(stone(62), ItemStack.empty())).build());
        InMemoryViewerItems side = side(); side.cursor(stone(10));
        Grid grid = pair(container);
        assertThat(engine.apply(grid, viewer, side, new Gesture.Spread(grid.bindings().keySet(), false))).isTrue();
        assertThat(container.contents()).containsExactly(stone(64), stone(5));
        assertThat(side.cursor()).isEqualTo(stone(3));
    }
    @Test void spread_one_placesOneEach() {
        SlotContainer container = service.container(storage(2).build());
        InMemoryViewerItems side = side(); side.cursor(stone(5));
        Grid grid = pair(container);
        assertThat(engine.apply(grid, viewer, side, new Gesture.Spread(grid.bindings().keySet(), true))).isTrue();
        assertThat(container.contents()).containsExactly(stone(1), stone(1));
        assertThat(side.cursor()).isEqualTo(stone(3));
    }
    @Test void pickup_atMost_takesPart() {
        SlotContainer container = service.container(storage(1).baseline(List.of(stone(5)))
                .policy(request -> TransferDecision.allowAtMost(2)).build());
        InMemoryViewerItems side = side();
        assertThat(apply(container, side, new Gesture.Pickup(SLOT, false))).isTrue();
        assertThat(side.cursor()).isEqualTo(stone(2));
        assertThat(container.get(0)).isEqualTo(stone(3));
    }
    @Test void place_atMost_placesPart() {
        SlotContainer container = service.container(storage(1).policy(request -> TransferDecision.allowAtMost(2)).build());
        InMemoryViewerItems side = side(); side.cursor(stone(5));
        assertThat(apply(container, side, new Gesture.Place(SLOT, false))).isTrue();
        assertThat(container.get(0)).isEqualTo(stone(2));
        assertThat(side.cursor()).isEqualTo(stone(3));
    }
    @Test void quickMoveOut_atMost_movesPart() {
        SlotContainer container = service.container(storage(1).baseline(List.of(stone(5)))
                .policy(request -> TransferDecision.allowAtMost(2)).build());
        InMemoryViewerItems side = side();
        assertThat(apply(container, side, new Gesture.QuickMoveOut(SLOT))).isTrue();
        assertThat(side.slot(0)).isEqualTo(stone(2));
        assertThat(container.get(0)).isEqualTo(stone(3));
    }
    @Test void quickMoveIn_atMost_limitsEachTransfer() {
        SlotContainer container = service.container(storage(2).policy(request -> TransferDecision.allowAtMost(2)).build());
        InMemoryViewerItems side = side(); side.slot(10, stone(64));
        assertThat(engine.apply(pair(container), viewer, side, new Gesture.QuickMoveIn(10))).isTrue();
        assertThat(container.contents()).containsExactly(stone(2), stone(2));
        assertThat(side.slot(10)).isEqualTo(stone(60));
    }
    @Test void spread_atMost_limitsEachTransfer() {
        SlotContainer container = service.container(storage(2).policy(request -> TransferDecision.allowAtMost(2)).build());
        InMemoryViewerItems side = side(); side.cursor(stone(10));
        Grid grid = pair(container);
        assertThat(engine.apply(grid, viewer, side, new Gesture.Spread(grid.bindings().keySet(), false))).isTrue();
        assertThat(container.contents()).containsExactly(stone(2), stone(2));
        assertThat(side.cursor()).isEqualTo(stone(6));
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
    @Test void template_quickMoveIn_storesOneDistributedCopy() {
        SlotContainer container = service.container(ContainerSpec.template(3)
                .baseline(List.of(stone(62), ItemStack.empty(), ItemStack.empty())).build());
        InMemoryViewerItems side = side(); side.slot(10, stone(64));
        Grid grid = Grid.builder(TITLE, 1).bindRange(0, 0, 3, container, 0, BindingMode.EDITABLE).build();
        assertThat(engine.apply(grid, viewer, side, new Gesture.QuickMoveIn(10))).isTrue();
        assertThat(container.contents()).containsExactly(stone(64), stone(62), ItemStack.empty());
        assertThat(side.slot(10)).isEqualTo(stone(64));
    }
    @Test void template_spread_storesEvenSplitCopies() {
        SlotContainer container = service.container(ContainerSpec.template(2).build());
        InMemoryViewerItems side = side(); side.cursor(stone(10));
        Grid grid = Grid.builder(TITLE, 1).bindRange(0, 0, 2, container, 0, BindingMode.EDITABLE).build();
        assertThat(engine.apply(grid, viewer, side, new Gesture.Spread(grid.bindings().keySet(), false))).isTrue();
        assertThat(container.contents()).containsExactly(stone(5), stone(5));
        assertThat(side.cursor()).isEqualTo(stone(10));
    }
    @Test void template_quickMoveOut_clearsIndexAndGivesNothing() {
        SlotContainer container = service.container(ContainerSpec.template(1).baseline(List.of(stone(5))).build());
        InMemoryViewerItems side = side();
        List<ItemStack> before = playerItems(side);
        assertThat(apply(container, side, new Gesture.QuickMoveOut(SLOT))).isTrue();
        assertThat(container.get(0).isEmpty()).isTrue();
        assertThat(playerItems(side)).isEqualTo(before);
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
        assertThat(fixture.shown(first, SLOT)).isEqualTo(stone(8));
        assertThat(fixture.shown(second, SLOT)).isEqualTo(stone(8));
        assertThat(backend.updates()).isEmpty();
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
