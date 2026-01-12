package com.ultikits.plugins.trade.entity;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Serializable representation of an ItemStack for JSON storage.
 * Contains complete item information including enchantments, metadata, etc.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SerializedItemStack {
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    /**
     * Material type name
     */
    private String type;
    
    /**
     * Item amount
     */
    private int amount;
    
    /**
     * Display name (with color codes)
     */
    private String displayName;
    
    /**
     * Lore lines
     */
    private List<String> lore;
    
    /**
     * Enchantments map: enchantment name -> level
     */
    private Map<String, Integer> enchantments;
    
    /**
     * Current durability
     */
    private int durability;
    
    /**
     * Max durability
     */
    private int maxDurability;
    
    /**
     * Custom model data
     */
    private Integer customModelData;
    
    /**
     * Item flags
     */
    private List<String> itemFlags;
    
    /**
     * Whether the item is unbreakable
     */
    private boolean unbreakable;
    
    /**
     * Create SerializedItemStack from ItemStack.
     *
     * @param item The ItemStack to serialize
     * @return SerializedItemStack instance
     */
    public static SerializedItemStack fromItemStack(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        
        SerializedItemStackBuilder builder = SerializedItemStack.builder()
            .type(item.getType().name())
            .amount(item.getAmount());
        
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // Display name
            if (meta.hasDisplayName()) {
                builder.displayName(meta.getDisplayName());
            }
            
            // Lore
            if (meta.hasLore()) {
                builder.lore(new ArrayList<>(meta.getLore()));
            }
            
            // Enchantments
            Map<Enchantment, Integer> enchants = item.getEnchantments();
            if (!enchants.isEmpty()) {
                Map<String, Integer> enchantMap = new HashMap<>();
                for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
                    enchantMap.put(entry.getKey().getKey().getKey(), entry.getValue());
                }
                builder.enchantments(enchantMap);
            }
            
            // Durability
            if (meta instanceof Damageable) {
                Damageable damageable = (Damageable) meta;
                int maxDura = item.getType().getMaxDurability();
                builder.maxDurability(maxDura);
                builder.durability(maxDura - damageable.getDamage());
            }
            
            // Custom model data
            if (meta.hasCustomModelData()) {
                builder.customModelData(meta.getCustomModelData());
            }
            
            // Item flags
            Set<ItemFlag> flags = meta.getItemFlags();
            if (!flags.isEmpty()) {
                builder.itemFlags(flags.stream()
                    .map(ItemFlag::name)
                    .collect(Collectors.toList()));
            }
            
            // Unbreakable
            builder.unbreakable(meta.isUnbreakable());
        }
        
        return builder.build();
    }
    
    /**
     * Convert to JSON string.
     *
     * @return JSON representation
     */
    public String toJson() {
        return GSON.toJson(this);
    }
    
    /**
     * Parse from JSON string.
     *
     * @param json JSON string
     * @return SerializedItemStack instance
     */
    public static SerializedItemStack fromJson(String json) {
        return GSON.fromJson(json, SerializedItemStack.class);
    }
    
    /**
     * Convert list of SerializedItemStack to JSON.
     *
     * @param items List of items
     * @return JSON array string
     */
    public static String listToJson(List<SerializedItemStack> items) {
        return GSON.toJson(items);
    }
    
    /**
     * Parse list from JSON.
     *
     * @param json JSON array string
     * @return List of SerializedItemStack
     */
    public static List<SerializedItemStack> listFromJson(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        Type listType = new TypeToken<List<SerializedItemStack>>(){}.getType();
        return GSON.fromJson(json, listType);
    }
    
    /**
     * Convert ItemStack collection to JSON.
     *
     * @param items Collection of ItemStacks
     * @return JSON array string
     */
    public static String itemsToJson(Collection<ItemStack> items) {
        List<SerializedItemStack> serialized = items.stream()
            .filter(Objects::nonNull)
            .map(SerializedItemStack::fromItemStack)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        return listToJson(serialized);
    }
    
    /**
     * Get a human-readable summary of the item.
     *
     * @return Summary string
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        if (displayName != null && !displayName.isEmpty()) {
            sb.append(displayName);
        } else {
            sb.append(type);
        }
        sb.append(" x").append(amount);
        
        if (enchantments != null && !enchantments.isEmpty()) {
            sb.append(" [");
            sb.append(enchantments.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", ")));
            sb.append("]");
        }
        
        return sb.toString();
    }
}
