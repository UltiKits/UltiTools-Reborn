package com.ultikits.ultitools.item;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.utils.MockBukkitHelper;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Measures the pinned MockBukkit's real component capabilities, not a mocked ItemStack. */
class MockBukkitDataComponentProbeTest {
    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Test
    void dataComponentTypes_registryConstants_areAvailable() {
        assertThat(List.of(DataComponentTypes.ITEM_NAME, DataComponentTypes.LORE,
                DataComponentTypes.ITEM_MODEL, DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,
                DataComponentTypes.TOOLTIP_DISPLAY)).hasSize(5);
        assertThat(DataComponentTypes.ITEM_NAME.getKey().toString()).isEqualTo("minecraft:item_name");
    }

    @Test
    void itemStack_setData_roundTripsItemName() {
        ItemStack stack = new ItemStack(Material.DIAMOND);
        Component name = Component.text("probe");
        stack.setData(DataComponentTypes.ITEM_NAME, name);
        assertThat(stack.getData(DataComponentTypes.ITEM_NAME)).isEqualTo(name);
    }

    @Test
    void componentValues_reportEqualityForUsedPaperTypes() {
        io.papermc.paper.datacomponent.item.ItemLore firstLore =
                io.papermc.paper.datacomponent.item.ItemLore.lore(List.of(Component.text("line")));
        io.papermc.paper.datacomponent.item.ItemLore secondLore =
                io.papermc.paper.datacomponent.item.ItemLore.lore(List.of(Component.text("line")));
        io.papermc.paper.datacomponent.item.TooltipDisplay firstTooltip =
                io.papermc.paper.datacomponent.item.TooltipDisplay.tooltipDisplay().hideTooltip(true).build();
        io.papermc.paper.datacomponent.item.TooltipDisplay secondTooltip =
                io.papermc.paper.datacomponent.item.TooltipDisplay.tooltipDisplay().hideTooltip(true).build();
        assertThat(firstLore.lines()).isEqualTo(secondLore.lines());
        assertThat(firstTooltip.hideTooltip()).isEqualTo(secondTooltip.hideTooltip());
        System.out.println("Component equality probe: ItemLore=" + firstLore.equals(secondLore)
                + ", TooltipDisplay=" + firstTooltip.equals(secondTooltip));
    }

    @Test
    void itemStack_editPersistentDataContainer_roundTripsMarker() {
        ItemStack stack = new ItemStack(Material.DIAMOND);
        NamespacedKey key = new NamespacedKey("probe", "marker");
        stack.editPersistentDataContainer(data -> data.set(key, PersistentDataType.STRING, "value"));
        assertThat(stack.getPersistentDataContainer().get(key, PersistentDataType.STRING)).isEqualTo("value");
    }
}
