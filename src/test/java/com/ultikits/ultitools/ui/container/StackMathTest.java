package com.ultikits.ultitools.ui.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultikits.ultitools.ui.ViewerItems;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

@Timeout(10)
class StackMathTest {
    @BeforeEach void setUp() { MockBukkitHelper.ensureCleanState(); MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkitHelper.safeUnmock(); }

    @Test void copiesNormalizeEmptyAndOwnTheirItems() {
        ItemStack stone = new ItemStack(Material.STONE, 5);
        List<ItemStack> copied = StackMath.copies(List.of(stone, ItemStack.empty()));
        stone.setAmount(1);
        assertThat(copied).containsExactly(new ItemStack(Material.STONE, 5), ItemStack.empty());
        assertThatThrownBy(() -> copied.add(stone)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> StackMath.copy(null)).isInstanceOf(NullPointerException.class);
        assertThat(StackMath.count(ItemStack.empty())).isZero();
        assertThat(StackMath.amount(stone, 0)).isEqualTo(ItemStack.empty());
        assertThat(StackMath.amount(ItemStack.empty(), 10)).isEqualTo(ItemStack.empty());
        assertThat(StackMath.empty(2)).containsExactly(ItemStack.empty(), ItemStack.empty());
    }
    @Test void roomUsesSimilarityAndActualMaxStackSize() {
        ItemStack pearl = new ItemStack(Material.ENDER_PEARL, 3);
        assertThat(StackMath.room(ItemStack.empty(), pearl)).isEqualTo(16);
        assertThat(StackMath.room(new ItemStack(Material.ENDER_PEARL, 15), pearl)).isOne();
        assertThat(StackMath.room(new ItemStack(Material.ENDER_PEARL, 20), pearl)).isZero();
        assertThat(StackMath.room(pearl, ItemStack.empty())).isZero();
        assertThat(StackMath.room(new ItemStack(Material.DIRT), pearl)).isZero();
        ItemStack named = pearl.clone();
        named.editMeta(meta -> meta.displayName(Component.text("different")));
        assertThat(StackMath.room(named, pearl)).isZero();
    }
    @Test void mergedAndSubtractedListsAreCopiesAndPreserveSimilarity() {
        ItemStack stone = new ItemStack(Material.STONE, 4);
        List<ItemStack> merged = StackMath.merged(List.of(stone, ItemStack.empty(), new ItemStack(Material.STONE, 5),
                new ItemStack(Material.DIRT, 3)));
        stone.setAmount(1);
        assertThat(merged).containsExactly(new ItemStack(Material.STONE, 9), new ItemStack(Material.DIRT, 3));
        List<ItemStack> remaining = StackMath.subtract(merged, List.of(new ItemStack(Material.STONE, 6),
                new ItemStack(Material.DIRT, 3)));
        assertThat(remaining).containsExactly(new ItemStack(Material.STONE, 3));
        assertThat(merged.getFirst()).isEqualTo(new ItemStack(Material.STONE, 9));
    }
    @Test void insertionMergesBeforeEmptyAndNeverUsesArmorSlots() {
        TestItems side = new TestItems(3);
        side.slot(1, new ItemStack(Material.ENDER_PEARL, 15));
        side.slot(2, new ItemStack(Material.DIRT, 64));
        ItemStack source = new ItemStack(Material.ENDER_PEARL, 16);
        assertThat(StackMath.fit(side, source)).isEqualTo(16);
        StackMath.insert(side, source, 16);
        assertThat(side.slot(0)).isEqualTo(new ItemStack(Material.ENDER_PEARL, 15));
        assertThat(side.slot(1)).isEqualTo(new ItemStack(Material.ENDER_PEARL, 16));
        assertThat(side.slot(2)).isEqualTo(new ItemStack(Material.DIRT, 64));
        assertThat(source.getAmount()).isEqualTo(16);
        side.slot(0, new ItemStack(Material.ENDER_PEARL, 16));
        assertThat(StackMath.fit(side, source)).isZero();
        StackMath.insert(side, source, 0);
        assertThat(side.cursor().isEmpty()).isTrue();
    }

    static final class TestItems implements ViewerItems {
        private ItemStack cursor = ItemStack.empty();
        private final List<ItemStack> slots;
        TestItems(int size) {
            slots = new ArrayList<>();
            for (int index = 0; index < size; index++) { slots.add(ItemStack.empty()); }
        }
        @Override public ItemStack cursor() { return cursor.clone(); }
        @Override public void cursor(ItemStack item) { cursor = item.clone(); }
        @Override public int inventorySize() { return slots.size(); }
        @Override public ItemStack slot(int index) { return slots.get(index).clone(); }
        @Override public void slot(int index, ItemStack item) { slots.set(index, item.clone()); }
    }
}
