package com.ultikits.ultitools.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;

import com.cryptomorin.xseries.XEnchantment;
import com.cryptomorin.xseries.XMaterial;

/**
 * A fluent builder for creating ItemStacks with various properties.
 * <p>
 * 用于创建具有各种属性的 ItemStack 的流式构建器。
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public class ItemStackBuilder {
    
    private final ItemStack itemStack;
    private ItemMeta itemMeta;
    
    /**
     * Creates a new ItemStackBuilder from an XMaterial.
     * <p>
     * 从 XMaterial 创建新的 ItemStackBuilder。
     *
     * @param material the XMaterial to use
     */
    public ItemStackBuilder(XMaterial material) {
        this.itemStack = material.parseItem();
        if (this.itemStack == null) {
            throw new IllegalArgumentException("Invalid material: " + material);
        }
        this.itemMeta = this.itemStack.getItemMeta();
    }
    
    /**
     * Creates a new ItemStackBuilder from a Material.
     * <p>
     * 从 Material 创建新的 ItemStackBuilder。
     *
     * @param material the Material to use
     */
    public ItemStackBuilder(Material material) {
        this.itemStack = new ItemStack(material);
        this.itemMeta = this.itemStack.getItemMeta();
    }
    
    /**
     * Creates a new ItemStackBuilder from an existing ItemStack.
     * <p>
     * 从现有的 ItemStack 创建新的 ItemStackBuilder。
     *
     * @param itemStack the ItemStack to clone
     */
    public ItemStackBuilder(ItemStack itemStack) {
        this.itemStack = itemStack.clone();
        this.itemMeta = this.itemStack.getItemMeta();
    }
    
    /**
     * Creates a new ItemStackBuilder from a material name string.
     * Uses XMaterial for cross-version compatibility.
     * <p>
     * 从材料名称字符串创建新的 ItemStackBuilder。
     * 使用 XMaterial 实现跨版本兼容。
     *
     * @param materialName the material name
     * @return a new ItemStackBuilder, or null if the material is invalid
     */
    @Nullable
    public static ItemStackBuilder of(String materialName) {
        Optional<XMaterial> material = XMaterial.matchXMaterial(materialName);
        return material.map(ItemStackBuilder::new).orElse(null);
    }
    
    /**
     * Creates a new ItemStackBuilder from an XMaterial.
     * <p>
     * 从 XMaterial 创建新的 ItemStackBuilder。
     *
     * @param material the XMaterial
     * @return a new ItemStackBuilder
     */
    public static ItemStackBuilder of(XMaterial material) {
        return new ItemStackBuilder(material);
    }
    
    /**
     * Creates a new ItemStackBuilder from a Material.
     * <p>
     * 从 Material 创建新的 ItemStackBuilder。
     *
     * @param material the Material
     * @return a new ItemStackBuilder
     */
    public static ItemStackBuilder of(Material material) {
        return new ItemStackBuilder(material);
    }
    
    /**
     * Creates a new ItemStackBuilder from an existing ItemStack.
     * <p>
     * 从现有 ItemStack 创建新的 ItemStackBuilder。
     *
     * @param itemStack the ItemStack to clone
     * @return a new ItemStackBuilder
     */
    public static ItemStackBuilder of(ItemStack itemStack) {
        return new ItemStackBuilder(itemStack);
    }
    
    /**
     * Sets the display name of the item.
     * <p>
     * 设置物品的显示名称。
     *
     * @param name the display name (supports color codes with &amp;)
     * @return this builder
     */
    public ItemStackBuilder name(String name) {
        if (itemMeta != null && name != null) {
            itemMeta.setDisplayName(name.replace("&", "§"));
        }
        return this;
    }
    
    /**
     * Sets the amount of items in the stack.
     * <p>
     * 设置堆叠中的物品数量。
     *
     * @param amount the amount
     * @return this builder
     */
    public ItemStackBuilder amount(int amount) {
        itemStack.setAmount(Math.max(1, Math.min(64, amount)));
        return this;
    }
    
    /**
     * Sets the lore of the item.
     * <p>
     * 设置物品的描述。
     *
     * @param lore the lore lines (supports color codes with &amp;)
     * @return this builder
     */
    public ItemStackBuilder lore(String... lore) {
        return lore(Arrays.asList(lore));
    }
    
    /**
     * Sets the lore of the item.
     * <p>
     * 设置物品的描述。
     *
     * @param lore the lore lines (supports color codes with &amp;)
     * @return this builder
     */
    public ItemStackBuilder lore(List<String> lore) {
        if (itemMeta != null && lore != null) {
            List<String> coloredLore = new ArrayList<>();
            for (String line : lore) {
                coloredLore.add(line.replace("&", "§"));
            }
            itemMeta.setLore(coloredLore);
        }
        return this;
    }
    
    /**
     * Adds lines to the existing lore.
     * <p>
     * 向现有描述添加行。
     *
     * @param lines the lines to add
     * @return this builder
     */
    public ItemStackBuilder addLore(String... lines) {
        if (itemMeta != null && lines != null) {
            List<String> lore = itemMeta.getLore();
            if (lore == null) {
                lore = new ArrayList<>();
            }
            for (String line : lines) {
                lore.add(line.replace("&", "§"));
            }
            itemMeta.setLore(lore);
        }
        return this;
    }
    
    /**
     * Adds an enchantment to the item.
     * <p>
     * 为物品添加附魔。
     *
     * @param enchantment the enchantment
     * @param level       the level
     * @return this builder
     */
    public ItemStackBuilder enchant(Enchantment enchantment, int level) {
        if (itemMeta != null && enchantment != null) {
            itemMeta.addEnchant(enchantment, level, true);
        }
        return this;
    }
    
    /**
     * Adds an enchantment using XEnchantment for cross-version compatibility.
     * <p>
     * 使用 XEnchantment 添加附魔以实现跨版本兼容。
     *
     * @param enchantment the XEnchantment
     * @param level       the level
     * @return this builder
     */
    public ItemStackBuilder enchant(XEnchantment enchantment, int level) {
        Enchantment ench = enchantment.getEnchant();
        if (ench != null) {
            enchant(ench, level);
        }
        return this;
    }
    
    /**
     * Removes an enchantment from the item.
     * <p>
     * 从物品移除附魔。
     *
     * @param enchantment the enchantment to remove
     * @return this builder
     */
    public ItemStackBuilder removeEnchant(Enchantment enchantment) {
        if (itemMeta != null && enchantment != null) {
            itemMeta.removeEnchant(enchantment);
        }
        return this;
    }
    
    /**
     * Makes the item glow without showing enchantments.
     * <p>
     * 使物品发光但不显示附魔。
     *
     * @return this builder
     */
    public ItemStackBuilder glow() {
        if (itemMeta != null) {
            Enchantment glowEnchant = XEnchantment.LUCK_OF_THE_SEA.getEnchant();
            if (glowEnchant != null) {
                itemMeta.addEnchant(glowEnchant, 1, true);
            }
            itemMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        return this;
    }
    
    /**
     * Adds item flags to the item.
     * <p>
     * 为物品添加物品标志。
     *
     * @param flags the flags to add
     * @return this builder
     */
    public ItemStackBuilder flags(ItemFlag... flags) {
        if (itemMeta != null && flags != null) {
            itemMeta.addItemFlags(flags);
        }
        return this;
    }
    
    /**
     * Hides all item attributes.
     * <p>
     * 隐藏所有物品属性。
     *
     * @return this builder
     */
    public ItemStackBuilder hideAttributes() {
        if (itemMeta != null) {
            itemMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        }
        return this;
    }
    
    /**
     * Hides all item flags.
     * <p>
     * 隐藏所有物品标志。
     *
     * @return this builder
     */
    public ItemStackBuilder hideAll() {
        if (itemMeta != null) {
            itemMeta.addItemFlags(ItemFlag.values());
        }
        return this;
    }
    
    /**
     * Makes the item unbreakable.
     * <p>
     * 使物品不可破坏。
     *
     * @return this builder
     */
    public ItemStackBuilder unbreakable() {
        if (itemMeta != null) {
            itemMeta.setUnbreakable(true);
        }
        return this;
    }
    
    /**
     * Sets the custom model data (1.14+).
     * <p>
     * 设置自定义模型数据（1.14+）。
     *
     * @param data the custom model data
     * @return this builder
     */
    public ItemStackBuilder customModelData(int data) {
        if (itemMeta != null) {
            try {
                itemMeta.setCustomModelData(data);
            } catch (NoSuchMethodError ignored) {
                // Method doesn't exist in older versions
            }
        }
        return this;
    }
    
    /**
     * Sets the color of leather armor.
     * <p>
     * 设置皮革护甲的颜色。
     *
     * @param color the color
     * @return this builder
     */
    public ItemStackBuilder leatherColor(Color color) {
        if (itemMeta instanceof LeatherArmorMeta && color != null) {
            ((LeatherArmorMeta) itemMeta).setColor(color);
        }
        return this;
    }
    
    /**
     * Sets the color of leather armor using RGB.
     * <p>
     * 使用 RGB 设置皮革护甲的颜色。
     *
     * @param red   red component (0-255)
     * @param green green component (0-255)
     * @param blue  blue component (0-255)
     * @return this builder
     */
    public ItemStackBuilder leatherColor(int red, int green, int blue) {
        return leatherColor(Color.fromRGB(red, green, blue));
    }
    
    /**
     * Sets the skull owner for player head items.
     * <p>
     * 设置玩家头颅的所有者。
     *
     * @param player the player
     * @return this builder
     */
    public ItemStackBuilder skullOwner(OfflinePlayer player) {
        if (itemMeta instanceof SkullMeta && player != null) {
            ((SkullMeta) itemMeta).setOwningPlayer(player);
        }
        return this;
    }
    
    /**
     * Sets the skull owner for player head items by name.
     * <p>
     * 通过名称设置玩家头颅的所有者。
     *
     * @param playerName the player name
     * @return this builder
     */
    @SuppressWarnings("deprecation")
    public ItemStackBuilder skullOwner(String playerName) {
        if (itemMeta instanceof SkullMeta && playerName != null) {
            ((SkullMeta) itemMeta).setOwner(playerName);
        }
        return this;
    }
    
    /**
     * Sets the skull owner for player head items by UUID.
     * <p>
     * 通过 UUID 设置玩家头颅的所有者。
     *
     * @param uuid the player's UUID
     * @return this builder
     */
    public ItemStackBuilder skullOwner(UUID uuid) {
        return skullOwner(Bukkit.getOfflinePlayer(uuid));
    }
    
    /**
     * Applies a custom modifier to the item meta.
     * <p>
     * 对物品元数据应用自定义修改器。
     *
     * @param modifier the modifier function
     * @return this builder
     */
    public ItemStackBuilder meta(java.util.function.Consumer<ItemMeta> modifier) {
        if (itemMeta != null && modifier != null) {
            modifier.accept(itemMeta);
        }
        return this;
    }
    
    /**
     * Applies a custom modifier to the item stack.
     * <p>
     * 对物品堆叠应用自定义修改器。
     *
     * @param modifier the modifier function
     * @return this builder
     */
    public ItemStackBuilder item(java.util.function.Consumer<ItemStack> modifier) {
        if (modifier != null) {
            modifier.accept(itemStack);
        }
        return this;
    }
    
    /**
     * Builds and returns the ItemStack.
     * <p>
     * 构建并返回 ItemStack。
     *
     * @return the built ItemStack
     */
    public ItemStack build() {
        if (itemMeta != null) {
            itemStack.setItemMeta(itemMeta);
        }
        return itemStack;
    }
    
    /**
     * Builds and returns a clone of the ItemStack.
     * <p>
     * 构建并返回 ItemStack 的副本。
     *
     * @return a clone of the built ItemStack
     */
    public ItemStack buildClone() {
        return build().clone();
    }
}
