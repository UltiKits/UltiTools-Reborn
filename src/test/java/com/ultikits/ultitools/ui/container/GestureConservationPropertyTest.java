package com.ultikits.ultitools.ui.container;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.ui.ContainerKind;
import com.ultikits.ultitools.ui.ContainerSpec;
import com.ultikits.ultitools.ui.Gesture;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSlot;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.TransferDecision;
import com.ultikits.ultitools.ui.ViewerItems;
import com.ultikits.ultitools.text.Text;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class GestureConservationPropertyTest extends FrameworkContainerTestSupport {
    private static final long SEED = 0x70C0FFEE1234L;
    private static final int STEPS = 2500;

    @Test void seededSequencesConserveStorageAndNeverChangeTemplateViewers() {
        Random random = new Random(SEED);
        for (ContainerKind kind : ContainerKind.values()) { runSequence(random, kind); }
        assertThat(STEPS).isGreaterThanOrEqualTo(2000);
    }
    private void runSequence(Random random, ContainerKind kind) {
        AtomicInteger policy = new AtomicInteger();
        AtomicInteger limit = new AtomicInteger();
        ContainerSpec.Builder spec = kind == ContainerKind.TEMPLATE ? ContainerSpec.template(9) : returning(9);
        SlotContainer container = service.container(spec.policy(request -> switch (policy.get()) {
            case 0 -> TransferDecision.allow();
            case 1 -> TransferDecision.allowAtMost(limit.get());
            case 2 -> TransferDecision.refuse(Text.plain("full"));
            default -> throw new IllegalStateException("seeded policy failure");
        }).build());
        Grid grid = grid(container);
        ViewerItems side = side();
        for (int step = 0; step < STEPS; step++) {
            configure(random, container, side, step);
            policy.set(step == 0 ? 0 : random.nextInt(4));
            limit.set(random.nextInt(9));
            Gesture gesture = step == 0 ? new Gesture.Place(FIRST, true) : gesture(random);
            List<ItemStack> before = container.contents();
            List<ItemStack> playerBefore = playerItems(side);
            boolean moved = containers.engine().apply(grid, viewer, side, gesture);
            List<ItemStack> after = container.contents();
            List<ItemStack> playerAfter = playerItems(side);
            String context = "seed=" + SEED + " step=" + step + " kind=" + kind + " gesture=" + gesture;
            if (kind == ContainerKind.STORAGE) {
                assertThat(totals(join(after, playerAfter))).as(context).isEqualTo(totals(join(before, playerBefore)));
            } else {
                assertThat(playerAfter).as(context).isEqualTo(playerBefore);
            }
            assertThat(moved).as(context).isEqualTo(!before.equals(after) || !playerBefore.equals(playerAfter));
            assertLimits(join(after, playerAfter), context);
        }
        container.drain();
    }
    private static void configure(Random random, SlotContainer container, ViewerItems side, int step) {
        container.setReadOnly(false);
        if (step % 40 == 0) {
            List<ItemStack> items = new ArrayList<>();
            for (int index = 0; index < container.size(); index++) { items.add(step == 0 ? ItemStack.empty() : randomItem(random)); }
            container.load(items);
            for (int index = 0; index < side.inventorySize(); index++) { side.slot(index, randomItem(random)); }
            side.cursor(step == 0 ? stone(4) : randomItem(random));
        }
        container.setReadOnly(step > 0 && random.nextInt(8) == 0);
    }
    private static Gesture gesture(Random random) {
        GridSlot slot = GridSlot.ofIndex(random.nextInt(9));
        return switch (random.nextInt(7)) {
            case 0 -> new Gesture.Pickup(slot, random.nextBoolean());
            case 1 -> new Gesture.Place(slot, random.nextBoolean());
            case 2 -> new Gesture.SwapWithCursor(slot);
            case 3 -> new Gesture.HotbarSwap(slot, random.nextInt(9));
            case 4 -> new Gesture.QuickMoveOut(slot);
            case 5 -> new Gesture.QuickMoveIn(random.nextInt(38) - 1);
            default -> new Gesture.Spread(Set.of(GridSlot.ofIndex(0), GridSlot.ofIndex(3), GridSlot.ofIndex(8)), random.nextBoolean());
        };
    }
    private static ItemStack randomItem(Random random) {
        Material type = switch (random.nextInt(6)) {
            case 0 -> Material.AIR;
            case 1 -> Material.DIRT;
            case 2 -> Material.ENDER_PEARL;
            case 3 -> Material.IRON_SWORD;
            default -> Material.STONE;
        };
        if (type.isAir()) { return ItemStack.empty(); }
        ItemStack item = new ItemStack(type);
        item.setAmount(1 + random.nextInt(item.getMaxStackSize()));
        if (random.nextInt(5) == 0) { item.editMeta(meta -> meta.displayName(Component.text("named"))); }
        return item;
    }
    private static List<ItemStack> playerItems(ViewerItems side) {
        List<ItemStack> result = new ArrayList<>();
        for (int index = 0; index < side.inventorySize(); index++) { result.add(side.slot(index)); }
        result.add(side.cursor());
        return result;
    }
    private static List<ItemStack> join(List<ItemStack> first, List<ItemStack> second) {
        List<ItemStack> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }
    private static Map<ItemStack, Integer> totals(List<ItemStack> items) {
        Map<ItemStack, Integer> totals = new LinkedHashMap<>();
        for (ItemStack item : items) {
            if (!item.isEmpty()) {
                ItemStack key = item.clone();
                key.setAmount(1);
                totals.merge(key, item.getAmount(), Integer::sum);
            }
        }
        return totals;
    }
    private static void assertLimits(List<ItemStack> items, String context) {
        for (ItemStack item : items) {
            assertThat(item.getAmount()).as(context).isBetween(0, item.getMaxStackSize());
        }
    }
}
