package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class ItemStackBuilderNativeTest {
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
    void currentMaterialNamesResolveWithoutAnExternalLookupLibrary() {
        assertThat(ItemStackBuilder.of("STONE").build().getType()).isEqualTo(Material.STONE);
        assertThat(ItemStackBuilder.of("minecraft:cactus_flower").build().getType())
                .isEqualTo(Material.CACTUS_FLOWER);
        assertThat(ItemStackBuilder.of("NOT_A_MATERIAL")).isNull();
    }

    @Test
    void nativeMaterialAndClonedItemEntrypointsRemainUsable() {
        ItemStack original = new ItemStack(Material.DIAMOND_SWORD);
        ItemStack built = ItemStackBuilder.of(original).amount(2).build();
        assertThat(built.getType()).isEqualTo(Material.DIAMOND_SWORD);
        assertThat(original.getAmount()).isEqualTo(1);
        assertThat(built.getAmount()).isEqualTo(2);
        assertThat(ItemStackBuilder.of(Material.STONE).build().getType()).isEqualTo(Material.STONE);
    }

    @Test
    void registryResolvedEnchantmentsCanBeAddedAndRemoved() {
        Enchantment enchantment = Registry.ENCHANTMENT.getOrThrow(NamespacedKey.minecraft("unbreaking"));
        ItemStack item = ItemStackBuilder.of(Material.DIAMOND_SWORD).enchant(enchantment, 3).build();
        assertThat(item.getEnchantmentLevel(enchantment)).isEqualTo(3);
        assertThat(ItemStackBuilder.of(item).removeEnchant(enchantment).build().getEnchantments()).isEmpty();
    }

    @Test
    void glowRetainsItsOriginalEnchantmentAndHiddenFlag() {
        Enchantment glow = Registry.ENCHANTMENT.getOrThrow(NamespacedKey.minecraft("luck_of_the_sea"));
        ItemStack item = ItemStackBuilder.of(Material.PAPER).glow().build();
        assertThat(item.getEnchantmentLevel(glow)).isEqualTo(1);
        assertThat(item.getItemMeta().hasItemFlag(ItemFlag.HIDE_ENCHANTS)).isTrue();
    }
}
