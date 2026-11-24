package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.text.Text;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/** Pins both the initial rendered snapshot and the update queued by a replaced session's dependent. */
class ChestOpeningSnapshotTest extends ChestTestFixture {
    @Test void openingAndQueuedUpdateEachDisplayTheBindingUsedByGestures() {
        GridSession previous = open(Grid.builder(Text.plain("Previous"), 1).build());
        SlotContainer original = container(1, List.of(stack(Material.STONE, 8)));
        SlotContainer replacement = container(1, List.of(stack(Material.DIRT, 5)));
        Grid next = grid(replacement, BindingMode.EDITABLE);
        AtomicReference<ItemStack> openingImage = new AtomicReference<>();
        AtomicReference<ItemStack> updatedImage = new AtomicReference<>();
        InMemoryViewerItems firstPickup = new InMemoryViewerItems(36);
        InMemoryViewerItems nextPickup = new InMemoryViewerItems(36);
        UiBackend observing = mock(UiBackend.class);
        when(observing.priority()).thenReturn(10);
        when(observing.supports(any(UiIntent.class), any(Player.class))).thenReturn(true);
        doAnswer(call -> {
            GridSession opening = call.getArgument(0);
            backend.open(opening, call.getArgument(1));
            openingImage.set(top().getItem(0).clone());
            runtime.applyGesture(opening, firstPickup, new Gesture.Pickup(GridSlot.ofIndex(0), false));
            return null;
        }).when(observing).open(any(), any());
        doAnswer(call -> {
            GridSession updating = call.getArgument(0);
            backend.update(updating, call.getArgument(1), call.getArgument(2));
            updatedImage.set(top().getItem(0).clone());
            runtime.applyGesture(updating, nextPickup, new Gesture.Pickup(GridSlot.ofIndex(0), false));
            return null;
        }).when(observing).update(any(), any(), any());
        doAnswer(call -> {
            backend.close(call.getArgument(0));
            return null;
        }).when(observing).close(any());
        runtime.register(observing);
        GridSession session = service.show(player, grid(original, BindingMode.EDITABLE));
        previous.result().thenRun(() -> session.update(next));
        tick();
        assertThat(session.current()).isSameAs(next);
        assertThat(openingImage.get()).isEqualTo(stack(Material.STONE, 8));
        assertThat(firstPickup.cursor()).isEqualTo(openingImage.get());
        assertThat(updatedImage.get()).isEqualTo(stack(Material.DIRT, 5));
        assertThat(nextPickup.cursor()).isEqualTo(updatedImage.get());
        assertThat(original.get(0).isEmpty()).isTrue();
        assertThat(replacement.get(0).isEmpty()).isTrue();
    }
}
