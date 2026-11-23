package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.bukkit.Material;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Fixture setup only; the assertions here do not use the reference engine's arithmetic. */
abstract class EditableContractSupport {
    protected static final GridSlot SLOT = GridSlot.ofIndex(0);
    protected static final Text TITLE = Text.plain("Editable contract");
    protected ServerMock server;
    protected PlayerMock viewer;
    protected AbstractUiServiceContractTest.Fixture fixture;
    protected UiService service;
    protected GestureEngine engine;
    protected final Map<UUID, List<ItemStack>> undelivered = new LinkedHashMap<>();
    protected final List<UUID> undeliveredBatches = new ArrayList<>();
    protected abstract AbstractUiServiceContractTest.Fixture createFixture();
    @BeforeEach void setUpEditable() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        viewer = server.addPlayer("Editor");
        fixture = createFixture();
        service = fixture.service("EditableModule");
        engine = fixture.engine();
    }
    @AfterEach void tearDownEditable() {
        try {
            if (fixture != null) {
                fixture.close();
            }
        } finally {
            MockBukkitHelper.safeUnmock();
        }
    }
    protected ContainerSpec.Builder storage(int size) {
        return ContainerSpec.storage(size).disposition(Disposition.RETURN_TO_CONTRIBUTORS).endWhenUnviewed(false)
                .undelivered((player, items) -> {
                    undeliveredBatches.add(player);
                    undelivered.computeIfAbsent(player, ignored -> new ArrayList<>()).addAll(items);
                });
    }
    protected Grid grid(SlotContainer container) { return grid(container, BindingMode.EDITABLE); }
    protected Grid grid(SlotContainer container, BindingMode mode) {
        return Grid.builder(TITLE, 1).bind(SLOT, container, 0, mode).build();
    }
    protected InMemoryViewerItems side() { return new InMemoryViewerItems(36); }
    protected boolean apply(SlotContainer container, ViewerItems side, Gesture gesture) {
        return engine.apply(grid(container), viewer, side, gesture);
    }
    protected void settle() { fixture.advanceTicks(AbstractUiServiceContractTest.SETTLE_TICKS); }
    protected static ItemStack stone(int amount) { return stack(Material.STONE, amount); }
    protected static ItemStack dirt(int amount) { return stack(Material.DIRT, amount); }
    protected static ItemStack namedStone(int amount) {
        ItemStack item = stone(amount);
        item.editMeta(meta -> meta.displayName(Component.text("Named")));
        return item;
    }
    protected static ItemStack pearl(int amount) { return stack(Material.ENDER_PEARL, amount); }
    protected static ItemStack sword(int amount) { return stack(Material.IRON_SWORD, amount); }
    protected static ItemStack stack(Material type, int amount) { return amount == 0 ? ItemStack.empty() : new ItemStack(type, amount); }
    protected static int quantity(List<ItemStack> items, Material type) {
        return items.stream().filter(item -> !item.isEmpty() && item.getType() == type).mapToInt(ItemStack::getAmount).sum();
    }
    protected static List<ItemStack> inventory(ViewerItems side) {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < side.inventorySize(); i++) {
            items.add(side.slot(i));
        }
        return items;
    }
    protected static List<ItemStack> playerItems(ViewerItems side) {
        List<ItemStack> items = inventory(side);
        items.add(side.cursor());
        return items;
    }
    protected static Map<ItemStack, Integer> totals(List<ItemStack> items) {
        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        for (ItemStack item : items) {
            if (!item.isEmpty()) {
                ItemStack key = item.clone();
                key.setAmount(1);
                counts.merge(key, item.getAmount(), Integer::sum);
            }
        }
        return counts;
    }
    protected static <T> T completed(CompletionStage<T> stage) {
        assertThat(stage.toCompletableFuture()).as("contract operation must settle").isDone();
        return stage.toCompletableFuture().getNow(null);
    }
}
