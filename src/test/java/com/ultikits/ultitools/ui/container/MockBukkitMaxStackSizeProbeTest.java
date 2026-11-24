package com.ultikits.ultitools.ui.container;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.ui.Gesture;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.ViewerItems;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.List;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class MockBukkitMaxStackSizeProbeTest extends FrameworkContainerTestSupport {
    @Test void componentRoundTripsButMockBukkitStillUsesTheMaterialLimit() {
        ItemStack custom = stone(16);
        custom.setData(DataComponentTypes.MAX_STACK_SIZE, 16);
        assertThat(custom.getData(DataComponentTypes.MAX_STACK_SIZE)).isEqualTo(16);
        assertThat(custom.getMaxStackSize()).as("MockBukkit 4.123.0 measurement, not a Paper guarantee").isEqualTo(64);
        assertThat(StackMath.room(ItemStack.empty(), custom)).isEqualTo(64);
        SlotContainer container = service.container(returning(2).build());
        ViewerItems side = side();
        side.slot(0, custom);
        assertThat(containers.engine().apply(grid(container), viewer, side, new Gesture.QuickMoveIn(0))).isTrue();
        side.slot(0, custom);
        assertThat(containers.engine().apply(grid(container), viewer, side, new Gesture.QuickMoveIn(0))).isTrue();
        ItemStack combined = custom.clone();
        combined.setAmount(32);
        assertThat(container.contents()).isEqualTo(List.of(combined, ItemStack.empty()));
        assertThat(side.slot(0).isEmpty()).isTrue();
    }
}
