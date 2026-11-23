package com.ultikits.ultitools.item;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.utils.MockBukkitHelper;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
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
    void componentValues_mockBukkitEquality_isAsMeasured() {
        ItemLore firstLore = ItemLore.lore(List.of(Component.text("line")));
        ItemLore secondLore = ItemLore.lore(List.of(Component.text("line")));
        TooltipDisplay firstTooltip = TooltipDisplay.tooltipDisplay().hideTooltip(true).build();
        TooltipDisplay secondTooltip = TooltipDisplay.tooltipDisplay().hideTooltip(true).build();
        assertThat(firstLore.lines()).isEqualTo(secondLore.lines());
        assertThat(firstLore).as("MockBukkit 4.123.0 measurement, not Paper; see U5").isNotEqualTo(secondLore);
        assertThat(firstTooltip).as("MockBukkit 4.123.0 measurement, not Paper; see U5").isEqualTo(secondTooltip);
    }

    @Test
    void itemStack_editPersistentDataContainer_roundTripsMarker() {
        ItemStack stack = new ItemStack(Material.DIAMOND);
        NamespacedKey key = new NamespacedKey("probe", "marker");
        stack.editPersistentDataContainer(data -> data.set(key, PersistentDataType.STRING, "value"));
        assertThat(stack.getPersistentDataContainer().get(key, PersistentDataType.STRING)).isEqualTo("value");
    }
}
