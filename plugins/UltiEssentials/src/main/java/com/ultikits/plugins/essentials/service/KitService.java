package com.ultikits.plugins.essentials.service;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.entity.KitClaimData;
import com.ultikits.plugins.essentials.entity.KitData;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.interfaces.DataOperator;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import javax.annotation.Nullable;
import com.ultikits.ultitools.annotations.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service for managing kits.
 * <p>
 * 管理礼包的服务。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Slf4j
@Service
public class KitService {

    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private EssentialsConfig config;

    private DataOperator<KitData> kitOperator;
    private DataOperator<KitClaimData> claimOperator;

    /**
     * Initializes the service with data operators.
     * Automatically called by the IoC container after construction.
     */
    @PostConstruct
    public void init() {
        this.kitOperator = plugin.getDataOperator(KitData.class);
        this.claimOperator = plugin.getDataOperator(KitClaimData.class);
    }
    
    /**
     * Gets all kits.
     */
    public List<KitData> getAllKits() {
        return kitOperator.getAll().stream()
            .filter(KitData::isEnabled)
            .collect(Collectors.toList());
    }
    
    /**
     * Gets kits available to a player.
     */
    public List<KitData> getAvailableKits(Player player) {
        return getAllKits().stream()
            .filter(kit -> canUseKit(player, kit))
            .collect(Collectors.toList());
    }
    
    /**
     * Gets a kit by name.
     */
    @Nullable
    public KitData getKit(String name) {
        return kitOperator.query()
            .where("name").eq(name.toLowerCase())
            .first();
    }
    
    /**
     * Checks if a player can use a kit (permission check only).
     */
    public boolean canUseKit(Player player, KitData kit) {
        if (kit.getPermission() == null || kit.getPermission().isEmpty()) {
            return true;
        }
        return player.hasPermission(kit.getPermission());
    }
    
    /**
     * Creates a new kit from player's inventory.
     *
     * @param name       kit name
     * @param items      items to include
     * @param cooldown   cooldown in seconds (-1 for one-time)
     * @param permission required permission (null for none)
     * @param createdBy  creator's UUID
     * @return result of the operation
     */
    public KitResult createKit(String name, ItemStack[] items, long cooldown, 
                                @Nullable String permission, UUID createdBy) {
        if (!config.isKitEnabled()) {
            return KitResult.DISABLED;
        }
        
        String normalizedName = name.toLowerCase().trim();
        if (normalizedName.isEmpty() || normalizedName.length() > 32) {
            return KitResult.INVALID_NAME;
        }
        
        if (getKit(normalizedName) != null) {
            return KitResult.ALREADY_EXISTS;
        }
        
        // Filter out air and null items
        ItemStack[] validItems = Arrays.stream(items)
            .filter(item -> item != null && item.getType() != Material.AIR)
            .toArray(ItemStack[]::new);
        
        if (validItems.length == 0) {
            return KitResult.EMPTY_KIT;
        }
        
        String serializedItems;
        try {
            serializedItems = serializeItems(validItems);
        } catch (IOException e) {
            log.error("Failed to serialize kit items", e);
            return KitResult.SERIALIZATION_ERROR;
        }
        
        KitData kit = KitData.builder()
            .uuid(UUID.randomUUID())
            .name(normalizedName)
            .displayName(name)
            .description("")
            .items(serializedItems)
            .permission(permission)
            .cooldown(cooldown)
            .enabled(true)
            .icon(validItems[0].getType().name())
            .createdBy(createdBy.toString())
            .createdAt(System.currentTimeMillis())
            .build();
        
        kitOperator.insert(kit);
        return KitResult.SUCCESS;
    }
    
    /**
     * Deletes a kit.
     */
    public boolean deleteKit(String name) {
        KitData kit = getKit(name.toLowerCase().trim());
        if (kit == null) {
            return false;
        }
        kitOperator.delById(kit.getId());
        return true;
    }
    
    /**
     * Claims a kit for a player.
     */
    public ClaimResult claimKit(Player player, String kitName) {
        if (!config.isKitEnabled()) {
            return ClaimResult.DISABLED;
        }
        
        KitData kit = getKit(kitName.toLowerCase().trim());
        if (kit == null) {
            return ClaimResult.NOT_FOUND;
        }
        
        if (!kit.isEnabled()) {
            return ClaimResult.NOT_FOUND;
        }
        
        if (!canUseKit(player, kit)) {
            return ClaimResult.NO_PERMISSION;
        }
        
        // Check cooldown
        KitClaimData claim = getClaimData(player.getUniqueId(), kit.getName());
        if (claim != null) {
            if (kit.isOneTime()) {
                return ClaimResult.ALREADY_CLAIMED;
            }
            
            long remaining = getRemainingCooldown(player.getUniqueId(), kit);
            if (remaining > 0) {
                return ClaimResult.ON_COOLDOWN;
            }
        }
        
        // Deserialize and give items
        ItemStack[] items;
        try {
            items = deserializeItems(kit.getItems());
        } catch (IOException | ClassNotFoundException e) {
            log.error("Failed to deserialize kit items for kit: " + kit.getName(), e);
            return ClaimResult.ERROR;
        }
        
        // Check inventory space
        int emptySlots = 0;
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType() == Material.AIR) {
                emptySlots++;
            }
        }
        
        if (emptySlots < items.length) {
            return ClaimResult.INVENTORY_FULL;
        }
        
        // Give items
        for (ItemStack item : items) {
            player.getInventory().addItem(item.clone());
        }
        
        // Update claim record
        updateClaimData(player.getUniqueId(), kit.getName());
        
        return ClaimResult.SUCCESS;
    }
    
    /**
     * Gets remaining cooldown for a kit.
     */
    public long getRemainingCooldown(UUID playerUuid, KitData kit) {
        if (kit.isOneTime()) {
            KitClaimData claim = getClaimData(playerUuid, kit.getName());
            return claim != null ? -1 : 0;
        }
        
        KitClaimData claim = getClaimData(playerUuid, kit.getName());
        if (claim == null) {
            return 0;
        }
        
        long cooldownEnd = claim.getLastClaim() + (kit.getCooldown() * 1000);
        long remaining = cooldownEnd - System.currentTimeMillis();
        return Math.max(0, remaining);
    }
    
    /**
     * Gets kit claim data for a player.
     */
    @Nullable
    private KitClaimData getClaimData(UUID playerUuid, String kitName) {
        List<KitClaimData> claims = claimOperator.query()
            .where("player_uuid").eq(playerUuid.toString())
            .list();

        return claims.stream()
            .filter(c -> c.getKitName().equalsIgnoreCase(kitName))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Updates or creates claim data.
     */
    private void updateClaimData(UUID playerUuid, String kitName) {
        KitClaimData existing = getClaimData(playerUuid, kitName);
        
        if (existing != null) {
            existing.setLastClaim(System.currentTimeMillis());
            existing.setClaimCount(existing.getClaimCount() + 1);
            try {
                claimOperator.update(existing);
            } catch (IllegalAccessException e) {
                log.error("Failed to update kit claim data", e);
            }
        } else {
            KitClaimData claim = KitClaimData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .kitName(kitName)
                .lastClaim(System.currentTimeMillis())
                .claimCount(1)
                .build();
            claimOperator.insert(claim);
        }
    }
    
    /**
     * Formats cooldown duration.
     */
    public static String formatCooldown(long millis) {
        if (millis <= 0) {
            return "可用";
        }
        
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;
        
        StringBuilder sb = new StringBuilder();
        if (hours > 0) sb.append(hours).append("小时 ");
        if (minutes > 0) sb.append(minutes).append("分钟 ");
        if (seconds > 0 || sb.length() == 0) sb.append(seconds).append("秒");
        
        return sb.toString().trim();
    }
    
    /**
     * Serializes items to Base64 string.
     */
    private String serializeItems(ItemStack[] items) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
        
        dataOutput.writeInt(items.length);
        for (ItemStack item : items) {
            dataOutput.writeObject(item);
        }
        
        dataOutput.close();
        return Base64Coder.encodeLines(outputStream.toByteArray());
    }
    
    /**
     * Deserializes items from Base64 string.
     */
    private ItemStack[] deserializeItems(String data) throws IOException, ClassNotFoundException {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(data));
        BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
        
        int size = dataInput.readInt();
        ItemStack[] items = new ItemStack[size];
        
        for (int i = 0; i < size; i++) {
            items[i] = (ItemStack) dataInput.readObject();
        }
        
        dataInput.close();
        return items;
    }
    
    public enum KitResult {
        SUCCESS,
        ALREADY_EXISTS,
        INVALID_NAME,
        EMPTY_KIT,
        SERIALIZATION_ERROR,
        DISABLED
    }
    
    public enum ClaimResult {
        SUCCESS,
        NOT_FOUND,
        NO_PERMISSION,
        ALREADY_CLAIMED,
        ON_COOLDOWN,
        INVENTORY_FULL,
        ERROR,
        DISABLED
    }
}
