package com.ultikits.ultitools.ui.container;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.BindingMode;
import com.ultikits.ultitools.ui.ContainerState;
import com.ultikits.ultitools.ui.EndOutcome;
import com.ultikits.ultitools.ui.Gesture;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSlot;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.TransferDecision;
import com.ultikits.ultitools.ui.ViewerItems;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@Timeout(30)
class ReturnAndSharedGridConservationTest extends FrameworkContainerTestSupport {
    private static final long SEED = 0x70C02C0FFEE1L;

    @Test void returnEndConservesDeliveredOfflineOverflowAndUnattributedItemsExactlyOnce() {
        SlotContainer container = service.container(returning(2).baseline(List.of(stone(5), ItemStack.empty())).build());
        PlayerMock other = server.addPlayer("OfflineContributor");
        ViewerItems firstSide = side();
        firstSide.cursor(stone(8));
        ViewerItems otherSide = side();
        otherSide.cursor(new ItemStack(Material.DIRT, 6));
        assertThat(containers.engine().apply(grid(container), viewer, firstSide, new Gesture.Place(FIRST, false))).isTrue();
        assertThat(containers.engine().apply(grid(container), other, otherSide,
                new Gesture.Place(GridSlot.ofIndex(1), false))).isTrue();
        assertThat(firstSide.cursor().isEmpty()).isTrue();
        assertThat(otherSide.cursor().isEmpty()).isTrue();
        fixture.disconnect(other);
        for (int index = 0; index < 36; index++) { viewer.getInventory().setItem(index, new ItemStack(Material.DIRT, 64)); }
        viewer.getInventory().setItem(0, stone(60));
        Map<ItemStack, Integer> before = totals(container.contents());
        Map<ItemStack, Integer> inventoryBefore = totals(Arrays.asList(viewer.getInventory().getStorageContents()));

        CompletionStage<EndOutcome> ending = container.end();
        fixture.advanceTicks(3);

        assertThat(completed(ending)).isEqualTo(EndOutcome.RETURNED);
        assertThat(container.state()).isEqualTo(ContainerState.ENDED);
        assertThat(viewer.getInventory().getItem(0)).isEqualTo(stone(64));
        List<ItemStack> dropped = viewer.getWorld().getEntitiesByClass(Item.class).stream().map(Item::getItemStack).toList();
        assertThat(dropped).containsExactly(stone(4));
        assertThat(undelivered).doesNotContainKey(viewer.getUniqueId());
        assertThat(undelivered.get(other.getUniqueId())).containsExactly(new ItemStack(Material.DIRT, 6));
        assertThat(undelivered.get(new UUID(0, 0))).containsExactly(stone(5));
        Map<ItemStack, Integer> accounted = totals(Arrays.asList(viewer.getInventory().getStorageContents()));
        inventoryBefore.forEach((item, amount) -> accounted.merge(item, -amount, Integer::sum));
        totals(dropped).forEach((item, amount) -> accounted.merge(item, amount, Integer::sum));
        totals(undelivered.values().stream().flatMap(List::stream).toList())
                .forEach((item, amount) -> accounted.merge(item, amount, Integer::sum));
        accounted.values().removeIf(amount -> amount == 0);
        assertThat(accounted).isEqualTo(before);
        Map<UUID, List<ItemStack>> once = new LinkedHashMap<>();
        undelivered.forEach((player, items) -> once.put(player, List.copyOf(items)));
        assertThat(completed(container.end())).isEqualTo(EndOutcome.RETURNED);
        fixture.advanceTicks(3);
        assertThat(undelivered).isEqualTo(once);
        assertThat(viewer.getInventory().getItem(0)).isEqualTo(stone(64));
        assertThat(viewer.getWorld().getEntitiesByClass(Item.class).stream().map(Item::getItemStack).toList()).isEqualTo(dropped);
    }

    @Test void oneGridWithTwoContainersConservesEverySeededGesture() {
        AtomicInteger policy = new AtomicInteger();
        SlotContainer first = service.container(returning(2).baseline(List.of(stone(60), ItemStack.empty())).build());
        SlotContainer second = service.container(returning(2).baseline(List.of(stone(62), ItemStack.empty()))
                .policy(request -> switch (policy.get()) {
                    case 0 -> TransferDecision.allow();
                    case 1 -> TransferDecision.allowAtMost(2);
                    default -> TransferDecision.refuse();
                }).build());
        Grid grid = Grid.builder(Text.plain("Two containers"), 1)
                .bindRange(0, 0, 2, first, 0, BindingMode.EDITABLE)
                .bindRange(0, 2, 2, second, 0, BindingMode.EDITABLE).build();
        ViewerItems side = side();
        side.slot(0, stone(10));
        Map<ItemStack, Integer> invariant = totals(allItems(first, second, side));
        assertThat(containers.engine().apply(grid, viewer, side, new Gesture.QuickMoveIn(0))).isTrue();
        assertThat(first.get(0)).isEqualTo(stone(64));
        assertThat(second.get(0)).isEqualTo(stone(64));
        assertThat(first.get(1)).isEqualTo(stone(4));
        assertThat(side.slot(0).isEmpty()).isTrue();
        assertThat(totals(allItems(first, second, side))).isEqualTo(invariant);
        Random random = new Random(SEED);
        for (int step = 0; step < 2500; step++) {
            policy.set(random.nextInt(3));
            Gesture gesture = gesture(random, step);
            containers.engine().apply(grid, viewer, side, gesture);
            assertThat(totals(allItems(first, second, side)))
                    .as("seed=%d step=%d gesture=%s", SEED, step, gesture).isEqualTo(invariant);
        }
        List<ItemStack> transferred = new ArrayList<>(first.drain());
        transferred.addAll(second.drain());
        transferred.addAll(viewerItems(side));
        assertThat(totals(transferred)).as("seed=%d after draining both containers", SEED).isEqualTo(invariant);
    }

    private static Gesture gesture(Random random, int step) {
        GridSlot slot = GridSlot.ofIndex(random.nextInt(4));
        return switch (step % 7) {
            case 0 -> new Gesture.Pickup(slot, random.nextBoolean());
            case 1 -> new Gesture.Place(slot, random.nextBoolean());
            case 2 -> new Gesture.SwapWithCursor(slot);
            case 3 -> new Gesture.HotbarSwap(slot, random.nextInt(9));
            case 4 -> new Gesture.QuickMoveOut(slot);
            case 5 -> new Gesture.QuickMoveIn(random.nextInt(36));
            default -> new Gesture.Spread(Set.of(FIRST, GridSlot.ofIndex(3)), random.nextBoolean());
        };
    }

    private static List<ItemStack> allItems(SlotContainer first, SlotContainer second, ViewerItems side) {
        List<ItemStack> items = new ArrayList<>(first.contents());
        items.addAll(second.contents());
        items.addAll(viewerItems(side));
        return items;
    }

    private static List<ItemStack> viewerItems(ViewerItems side) {
        List<ItemStack> items = new ArrayList<>();
        items.add(side.cursor());
        for (int index = 0; index < side.inventorySize(); index++) { items.add(side.slot(index)); }
        return items;
    }

    private static Map<ItemStack, Integer> totals(List<ItemStack> items) {
        Map<ItemStack, Integer> result = new LinkedHashMap<>();
        for (ItemStack item : items) {
            if (item != null && !item.isEmpty()) {
                ItemStack key = item.clone();
                key.setAmount(1);
                result.merge(key, item.getAmount(), Integer::sum);
            }
        }
        return result;
    }
}
