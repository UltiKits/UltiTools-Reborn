package com.ultikits.plugins.backup.entity;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Backup content POJO (cold data).
 * Contains serialized inventory data, stored in YAML files on disk.
 * <p>
 * 备份内容 POJO（冷数据）。
 * 包含序列化的背包数据，存储在磁盘上的 YAML 文件中。
 *
 * @author wisdomme
 * @version 2.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BackupContent {
    
    /**
     * File header warning message.
     */
    public static final String FILE_HEADER = 
        "# !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
        "# DO NOT MODIFY THIS FILE! 请勿修改此文件！\n" +
        "# Any modification will cause checksum verification failure\n" +
        "# 任何修改都会导致校验和验证失败，备份将无法恢复\n" +
        "# !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
        "# Checksum: %s\n" +
        "\n";
    
    /**
     * Serialized inventory contents (YAML format).
     */
    private String inventoryContents;
    
    /**
     * Serialized armor contents (YAML format).
     */
    private String armorContents;
    
    /**
     * Serialized offhand item (YAML format).
     */
    private String offhandItem;
    
    /**
     * Serialized ender chest contents (YAML format).
     */
    private String enderchestContents;
    
    /**
     * Experience level.
     */
    private int expLevel;
    
    /**
     * Experience progress (0.0 - 1.0).
     */
    private float expProgress;
    
    /**
     * Create backup content from player.
     * <p>
     * 从玩家创建备份内容。
     *
     * @param player the player
     * @param backupArmor whether to backup armor
     * @param backupEnderchest whether to backup ender chest
     * @param backupExp whether to backup experience
     * @return the backup content
     */
    public static BackupContent fromPlayer(Player player, boolean backupArmor, 
            boolean backupEnderchest, boolean backupExp) {
        BackupContentBuilder builder = BackupContent.builder();
        
        // Serialize inventory
        builder.inventoryContents(serializeItems(player.getInventory().getStorageContents()));
        
        // Serialize armor
        if (backupArmor) {
            builder.armorContents(serializeItems(player.getInventory().getArmorContents()));
            builder.offhandItem(serializeItem(player.getInventory().getItemInOffHand()));
        }
        
        // Serialize ender chest
        if (backupEnderchest) {
            builder.enderchestContents(serializeItems(player.getEnderChest().getContents()));
        }
        
        // Experience
        if (backupExp) {
            builder.expLevel(player.getLevel());
            builder.expProgress(player.getExp());
        }
        
        return builder.build();
    }
    
    /**
     * Save content to file with SHA-256 checksum.
     * <p>
     * 将内容保存到文件（带 SHA-256 校验和）。
     *
     * @param file the file to save to
     * @return the SHA-256 checksum of the content
     * @throws IOException if save fails
     */
    public String saveToFile(File file) throws IOException {
        // Ensure parent directory exists
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        // Create YAML content
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("inventory", inventoryContents);
        yaml.set("armor", armorContents);
        yaml.set("offhand", offhandItem);
        yaml.set("enderchest", enderchestContents);
        yaml.set("expLevel", expLevel);
        yaml.set("expProgress", expProgress);

        String yamlContent = yaml.saveToString();

        // Calculate SHA-256 checksum
        String checksum = calculateChecksum(yamlContent);
        
        // Write file with header
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write(String.format(FILE_HEADER, checksum));
            writer.write(yamlContent);
        }
        
        return checksum;
    }
    
    /**
     * Load content from file.
     * <p>
     * 从文件加载内容。
     *
     * @param file the file to load from
     * @return the backup content
     * @throws IOException if load fails
     */
    public static BackupContent loadFromFile(File file) throws IOException {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        
        return BackupContent.builder()
            .inventoryContents(yaml.getString("inventory", ""))
            .armorContents(yaml.getString("armor", ""))
            .offhandItem(yaml.getString("offhand", ""))
            .enderchestContents(yaml.getString("enderchest", ""))
            .expLevel(yaml.getInt("expLevel", 0))
            .expProgress((float) yaml.getDouble("expProgress", 0.0))
            .build();
    }
    
    /**
     * Verify file checksum.
     * <p>
     * 验证文件校验和。
     *
     * @param file the file to verify
     * @param expectedChecksum the expected checksum
     * @return true if checksum matches
     * @throws IOException if read fails
     */
    public static boolean verifyChecksum(File file, String expectedChecksum) throws IOException {
        if (!file.exists() || expectedChecksum == null) {
            return false;
        }
        
        // Read file and extract YAML content (skip header comments)
        StringBuilder contentBuilder = new StringBuilder();
        boolean inContent = false;
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!inContent && !line.startsWith("#")) {
                    inContent = true;
                }
                if (inContent) {
                    contentBuilder.append(line).append("\n");
                }
            }
        }
        
        String actualChecksum = calculateChecksum(contentBuilder.toString().trim() + "\n");
        return expectedChecksum.equals(actualChecksum);
    }
    
    /**
     * Calculate SHA-256 checksum of string.
     * <p>
     * 计算字符串的 SHA-256 校验和。
     *
     * @param content the content to hash
     * @return the SHA-256 checksum (hex string)
     */
    public static String calculateChecksum(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
    
    /**
     * Restore content to player.
     * <p>
     * 将内容恢复到玩家。
     *
     * @param player the player
     * @param restoreArmor whether to restore armor
     * @param restoreEnderchest whether to restore ender chest
     * @param restoreExp whether to restore experience
     */
    public void restoreToPlayer(Player player, boolean restoreArmor, 
            boolean restoreEnderchest, boolean restoreExp) {
        // Clear current inventory
        player.getInventory().clear();
        
        // Restore inventory contents
        ItemStack[] contents = deserializeItems(inventoryContents);
        if (contents != null) {
            for (int i = 0; i < Math.min(contents.length, 36); i++) {
                if (contents[i] != null) {
                    player.getInventory().setItem(i, contents[i]);
                }
            }
        }
        
        // Restore armor
        if (restoreArmor && armorContents != null) {
            ItemStack[] armor = deserializeItems(armorContents);
            if (armor != null) {
                player.getInventory().setArmorContents(armor);
            }
            ItemStack offhand = deserializeItem(offhandItem);
            if (offhand != null) {
                player.getInventory().setItemInOffHand(offhand);
            }
        }
        
        // Restore ender chest
        if (restoreEnderchest && enderchestContents != null) {
            ItemStack[] enderChest = deserializeItems(enderchestContents);
            if (enderChest != null) {
                player.getEnderChest().setContents(enderChest);
            }
        }
        
        // Restore exp
        if (restoreExp) {
            player.setLevel(expLevel);
            player.setExp(expProgress);
        }
    }
    
    /**
     * Get deserialized inventory items.
     * <p>
     * 获取反序列化的背包物品。
     *
     * @return the inventory items
     */
    public ItemStack[] getInventoryItems() {
        return deserializeItems(inventoryContents);
    }
    
    /**
     * Get deserialized armor items.
     * <p>
     * 获取反序列化的护甲物品。
     *
     * @return the armor items
     */
    public ItemStack[] getArmorItems() {
        return deserializeItems(armorContents);
    }
    
    /**
     * Get deserialized offhand item.
     * <p>
     * 获取反序列化的副手物品。
     *
     * @return the offhand item
     */
    public ItemStack getOffhandItemStack() {
        return deserializeItem(offhandItem);
    }
    
    /**
     * Get deserialized ender chest items.
     * <p>
     * 获取反序列化的末影箱物品。
     *
     * @return the ender chest items
     */
    public ItemStack[] getEnderchestItems() {
        return deserializeItems(enderchestContents);
    }
    
    // ============ Serialization Utilities ============
    
    /**
     * Serialize items to YAML string.
     */
    private static String serializeItems(ItemStack[] items) {
        if (items == null) return "";
        
        YamlConfiguration yaml = new YamlConfiguration();
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null) {
                yaml.set("items." + i, items[i]);
            }
        }
        return yaml.saveToString();
    }
    
    /**
     * Serialize single item to YAML string.
     */
    private static String serializeItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return "";
        
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("item", item);
        return yaml.saveToString();
    }
    
    /**
     * Deserialize items from YAML string.
     */
    private static ItemStack[] deserializeItems(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(data);
            
            if (!yaml.isConfigurationSection("items")) {
                return null;
            }
            
            int maxSlot = 0;
            for (String key : yaml.getConfigurationSection("items").getKeys(false)) {
                int slot = Integer.parseInt(key);
                maxSlot = Math.max(maxSlot, slot);
            }
            
            ItemStack[] result = new ItemStack[maxSlot + 1];
            for (String key : yaml.getConfigurationSection("items").getKeys(false)) {
                int slot = Integer.parseInt(key);
                result[slot] = yaml.getItemStack("items." + key);
            }
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Deserialize single item from YAML string.
     */
    private static ItemStack deserializeItem(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(data);
            return yaml.getItemStack("item");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
