package com.ultikits.ultitools.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

/**
 * A fluent builder for creating ItemStacks with various properties.
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public class ItemStackBuilder {
    
    private final ItemStack itemStack;
    private ItemMeta itemMeta;
    
    /**
     * Creates a new ItemStackBuilder from a Material.
     *
     * @param material the Material to use
     */
    public ItemStackBuilder(Material material) {
        this.itemStack = new ItemStack(material);
        this.itemMeta = this.itemStack.getItemMeta();
    }
    
    /**
     * Creates a new ItemStackBuilder from an existing ItemStack.
     *
     * @param itemStack the ItemStack to clone
     */
    public ItemStackBuilder(ItemStack itemStack) {
        this.itemStack = itemStack.clone();
        this.itemMeta = this.itemStack.getItemMeta();
    }
    
    /**
     * Creates a new ItemStackBuilder from a current Bukkit material name or namespaced name.
     * Historical aliases, numeric material IDs and old names renamed after 1.20 return null.
     * Only names supported by the current server version are accepted; no legacy lookup is performed.
     *
     * @param materialName the material name
     * @return a new ItemStackBuilder, or null if the name is unknown or the material is not an item
     */
    @Nullable
    public static ItemStackBuilder of(String materialName) {
        Material material = Material.matchMaterial(materialName);
        return material == null || !material.isItem() ? null : new ItemStackBuilder(material);
    }
    
    /**
     * Creates a new ItemStackBuilder from a Material.
     *
     * @param material the Material
     * @return a new ItemStackBuilder
     */
    public static ItemStackBuilder of(Material material) {
        return new ItemStackBuilder(material);
    }
    
    /**
     * Creates a new ItemStackBuilder from an existing ItemStack.
     *
     * @param itemStack the ItemStack to clone
     * @return a new ItemStackBuilder
     */
    public static ItemStackBuilder of(ItemStack itemStack) {
        return new ItemStackBuilder(itemStack);
    }
    
    /**
     * Sets the display name of the item.
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
     *
     * @param lore the lore lines (supports color codes with &amp;)
     * @return this builder
     */
    public ItemStackBuilder lore(String... lore) {
        return lore(Arrays.asList(lore));
    }
    
    /**
     * Sets the lore of the item.
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
     * Removes an enchantment from the item.
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
     *
     * @return this builder
     */
    public ItemStackBuilder glow() {
        if (itemMeta != null) {
            Enchantment glowEnchant = Enchantment.LUCK_OF_THE_SEA;
            if (glowEnchant != null) {
                itemMeta.addEnchant(glowEnchant, 1, true);
            }
            itemMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        return this;
    }
    
    /**
     * Adds item flags to the item.
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
     *
     * @param uuid the player's UUID
     * @return this builder
     */
    public ItemStackBuilder skullOwner(UUID uuid) {
        return skullOwner(Bukkit.getOfflinePlayer(uuid));
    }
    
    /**
     * Applies a custom modifier to the item meta.
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
     *
     * @return a clone of the built ItemStack
     */
    public ItemStack buildClone() {
        return build().clone();
    }
}
