package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.dialog.DialogBackend;
import com.ultikits.ultitools.ui.dialog.DialogModel;
import com.ultikits.ultitools.ui.dialog.DialogPresenter;
import com.ultikits.ultitools.ui.dialog.DialogTicket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UiOpeningSafetyTest extends ChestTestFixture {
    @ParameterizedTest
    @CsvSource({"grid,false,false", "grid,true,false", "grid,false,true", "grid,true,true",
            "bound,false,false", "bound,true,false", "bound,false,true", "bound,true,true",
            "dialog,false,false", "dialog,true,false", "dialog,false,true", "dialog,true,true"})
    void backpackItemsReturnOrDropBeforeShowingWithoutLossOrDuplication(String kind, boolean full, boolean creative) {
        Backpack boundary = new Backpack(creative);
        if (full) {
            ItemStack[] filled = new ItemStack[36];
            Arrays.setAll(filled, index -> stack(Material.COBBLESTONE, 64));
            boundary.viewer.getInventory().setStorageContents(filled);
        }
        boundary.cursor.set(stack(Material.STONE, 4));
        boundary.matrix.set(new ItemStack[] {stack(Material.DIRT, 3), null, null, null});
        show(boundary, kind);
        tick();
        assertThat(boundary.events).containsExactly("close", "show");
        assertThat(boundary.cursor.get().isEmpty()).isTrue();
        assertThat(Arrays.stream(boundary.matrix.get()).allMatch(item -> item == null || item.isEmpty())).isTrue();
        assertThat(boundary.amount(Material.STONE)).isEqualTo(4);
        assertThat(boundary.amount(Material.DIRT)).isEqualTo(3);
        assertThat(boundary.drops.stream().mapToInt(ItemStack::getAmount).sum()).isEqualTo(full ? 7 : 0);
    }

    @ParameterizedTest
    @CsvSource({"grid,false", "grid,true", "bound,false", "bound,true", "dialog,false", "dialog,true"})
    void emptyBackpackAndForeignContainersDoNotReceiveAnExtraClose(String kind, boolean foreign) {
        Backpack boundary = new Backpack(false);
        if (foreign) {
            boundary.active.set(boundary.view(server.createInventory(null, 9), InventoryType.CHEST));
            boundary.cursor.set(stack(Material.STONE, 4));
        }
        show(boundary, kind);
        tick();
        assertThat(boundary.events).containsExactly("show");
        assertThat(boundary.viewer.getOpenInventory()).isNotNull();
    }

    @ParameterizedTest
    @CsvSource({"grid,true", "grid,false", "bound,true", "bound,false", "dialog,true", "dialog,false"})
    void eitherCursorOrCraftingMatrixAloneTriggersVanillaCleanup(String kind, boolean cursor) {
        Backpack boundary = new Backpack(false);
        if (cursor) {
            boundary.cursor.set(stack(Material.STONE, 4));
        } else {
            boundary.matrix.set(new ItemStack[] {stack(Material.DIRT, 3), null, null, null});
        }
        show(boundary, kind);
        tick();
        assertThat(boundary.events).containsExactly("close", "show");
        assertThat(boundary.amount(cursor ? Material.STONE : Material.DIRT)).isEqualTo(cursor ? 4 : 3);
    }

    private void show(Backpack boundary, String kind) {
        if (kind.equals("dialog")) {
            DialogPresenter presenter = new DialogPresenter() {
                @Override public void show(Player player, DialogModel model, DialogTicket ticket) {
                    boundary.events.add("show");
                }
                @Override public void dismiss(Player player) { /* Nothing to dismiss in this test. */ }
            };
            runtime.register(new DialogBackend(messages, presenter, Logger.getAnonymousLogger(), runtime::ownerName));
            service.show(boundary.viewer, Notice.builder(Text.plain("Notice")).build());
        } else {
            Grid grid = kind.equals("bound") ? grid(container(1, List.of(ItemStack.empty())), BindingMode.EDITABLE)
                    : Grid.builder(Text.plain("Grid"), 1).build();
            service.show(boundary.viewer, grid);
        }
    }

    /** Models Paper's two menus, absent from MockBukkit (its default backpack has a null top inventory). */
    private final class Backpack {
        private final SyncPlayer viewer = spy(player);
        private final AtomicReference<ItemStack> cursor = new AtomicReference<>(ItemStack.empty());
        private final AtomicReference<ItemStack[]> matrix = new AtomicReference<>(new ItemStack[4]);
        private final AtomicReference<InventoryView> active = new AtomicReference<>();
        private final List<ItemStack> drops = new ArrayList<>();
        private final List<String> events = new ArrayList<>();

        Backpack(boolean creative) {
            CraftingInventory crafting = mock(CraftingInventory.class);
            when(crafting.getMatrix()).thenAnswer(call -> matrix.get().clone());
            active.set(view(crafting, creative ? InventoryType.CREATIVE : InventoryType.CRAFTING));
            doAnswer(call -> active.get()).when(viewer).getOpenInventory();
            doAnswer(call -> cursor.get()).when(viewer).getItemOnCursor();
            doAnswer(call -> {
                events.add("show");
                active.set(view(call.getArgument(0), InventoryType.CHEST));
                return active.get();
            }).when(viewer).openInventory(any(Inventory.class));
            // Only model vanilla cleanup, never the framework's decision to invoke it.
            doAnswer(call -> {
                events.add("close");
                List<ItemStack> returned = new ArrayList<>();
                returned.add(cursor.getAndSet(ItemStack.empty()));
                returned.addAll(Arrays.asList(matrix.getAndSet(new ItemStack[4])));
                returned.stream().filter(item -> item != null && !item.isEmpty()).forEach(this::returnOrDrop);
                return null;
            }).when(viewer).closeInventory();
        }

        InventoryView view(Inventory top, InventoryType type) {
            InventoryView view = mock(InventoryView.class);
            when(view.getTopInventory()).thenReturn(top);
            when(view.getType()).thenReturn(type);
            when(view.getPlayer()).thenReturn(viewer);
            return view;
        }

        private void returnOrDrop(ItemStack item) {
            // MockBukkit's addItem also scans armour slots; vanilla's backpack return uses storage only.
            for (int index = 0; index < 36; index++) {
                ItemStack current = viewer.getInventory().getItem(index);
                if (current == null || current.isEmpty()) {
                    viewer.getInventory().setItem(index, item.clone());
                    return;
                }
            }
            drops.add(item.clone());
        }

        int amount(Material material) {
            return Arrays.stream(viewer.getInventory().getStorageContents())
                    .filter(item -> item != null && item.getType() == material).mapToInt(ItemStack::getAmount).sum()
                    + drops.stream().filter(item -> item.getType() == material).mapToInt(ItemStack::getAmount).sum();
        }
    }
}
