package com.ultikits.ultitools.item.impl;

import com.ultikits.ultitools.item.ItemRenderer;
import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

/**
 * Compile checks for every replacement in COMPATIBILITY.md's item-construction migration table.
 * These examples are deliberately not executed: compiling an API call does not establish real-server behaviour.
 */
final class ItemConstructionMigrationCompileCheck {
    private ItemConstructionMigrationCompileCheck() {
        // Compile-only examples; no instances are needed.
    }

    static ItemStack named(ItemRenderer itemRenderer, Player player, MessageKey key, MessageKey key2) {
        ItemSpec spec = ItemSpec.builder(Material.DIAMOND).name(key).addLore(key2).build();
        return itemRenderer.render(spec, player);
    }

    static ItemSpec.Builder lore(ItemSpec.Builder builder, MessageKey key2, MessageKey key3, Component component) {
        builder.lore(List.of(Text.of(key2), Text.of(key3)));
        builder.addLore(key2).addLore(key3);
        return builder.addLore(Text.literal(component));
    }

    static ItemSpec.Builder amountsAndEnchantments(ItemSpec.Builder builder, int n, Enchantment e, int lvl) {
        builder.amount(n);
        builder.glint(true);
        builder.data(DataComponentTypes.ENCHANTMENTS, ItemEnchantments.itemEnchantments().add(e, lvl).build());
        return builder.remove(DataComponentTypes.ENCHANTMENTS);
    }

    static ItemSpec.Builder tooltipAndModel(ItemSpec.Builder builder, int n) {
        builder.hiddenInTooltip(Set.of(DataComponentTypes.ATTRIBUTE_MODIFIERS));
        builder.tooltipHidden(true);
        builder.data(DataComponentTypes.UNBREAKABLE);
        return builder.data(DataComponentTypes.CUSTOM_MODEL_DATA,
                CustomModelData.customModelData().addFloat(n).build());
    }

    static ItemSpec.Builder colour(ItemSpec.Builder builder, Color c) {
        return builder.data(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(c));
    }

    static ItemSpec.Builder rgb(ItemSpec.Builder builder, int r, int g, int b) {
        return builder.data(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(Color.fromRGB(r, g, b)));
    }

    static ItemSpec.Builder profiles(ItemSpec.Builder builder, OfflinePlayer player, String name, UUID uuid) {
        builder.data(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile(player.getPlayerProfile()));
        builder.data(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile().name(name).build());
        return builder.data(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile().uuid(uuid).build());
    }

    static ItemStack flags(ItemStack stack, ItemFlag[] flags, Enchantment e) {
        stack.removeEnchantment(e);
        stack.editMeta(meta -> meta.addItemFlags(flags));
        stack.editMeta(meta -> meta.addItemFlags(ItemFlag.values()));
        return stack;
    }

    static ItemStack consumers(ItemStack stack, Consumer<ItemMeta> metaModifier, Consumer<ItemStack> itemModifier) {
        stack.editMeta(metaModifier);
        itemModifier.accept(stack);
        return stack;
    }

    static ItemStack existing(ItemStack existingStack) {
        ItemStack stack = existingStack.clone();
        stack.setData(DataComponentTypes.MAX_STACK_SIZE, 16);
        stack.unsetData(DataComponentTypes.MAX_STACK_SIZE);
        return stack;
    }

    static ItemStack buildClone(ItemRenderer itemRenderer, ItemSpec spec, Player player) {
        ItemStack stack = itemRenderer.render(spec, player);
        return stack.clone();
    }

    static @Nullable ItemSpec.Builder materialName(String name) {
        Material matched = Material.matchMaterial(name);
        return matched != null && matched.isItem() && !matched.isAir() ? ItemSpec.builder(matched) : null;
    }
}
