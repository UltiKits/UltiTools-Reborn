package com.ultikits.plugins.backup.service;

import com.ultikits.plugins.backup.UltiBackup;
import com.ultikits.plugins.backup.config.BackupConfig;
import com.ultikits.plugins.backup.entity.BackupData;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Service for inventory backup operations.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service
public class BackupService {
    
    @Autowired
    private BackupConfig config;
    
    private DataOperator<BackupData> dataOperator;
    
    /**
     * Initialize the service.
     */
    public void init() {
        this.dataOperator = UltiBackup.getInstance().getDataOperator(BackupData.class);
        
        // Start auto backup task
        if (config.isAutoBackupEnabled() && config.getAutoBackupInterval() > 0) {
            Bukkit.getScheduler().runTaskTimerAsynchronously(
                UltiTools.getInstance(),
                this::autoBackupAll,
                config.getAutoBackupInterval() * 60 * 20L,
                config.getAutoBackupInterval() * 60 * 20L
            );
        }
    }
    
    /**
     * Create a backup for a player.
     */
    public BackupData createBackup(Player player, String reason) {
        BackupData backup = BackupData.fromPlayer(player, reason);
        
        // Serialize inventory
        backup.setInventoryContents(serializeItems(player.getInventory().getStorageContents()));
        
        // Serialize armor
        if (config.isBackupArmor()) {
            backup.setArmorContents(serializeItems(player.getInventory().getArmorContents()));
            backup.setOffhandItem(serializeItem(player.getInventory().getItemInOffHand()));
        }
        
        // Serialize ender chest
        if (config.isBackupEnderchest()) {
            backup.setEnderchestContents(serializeItems(player.getEnderChest().getContents()));
        }
        
        // Save to database
        dataOperator.insert(backup);
        
        // Clean up old backups
        cleanupOldBackups(player.getUniqueId());
        
        return backup;
    }
    
    /**
     * Get all backups for a player.
     */
    public List<BackupData> getBackups(UUID playerUuid) {
        List<BackupData> backups = dataOperator.getAll(
            WhereCondition.builder()
                .column("player_uuid")
                .value(playerUuid.toString())
                .build()
        );
        
        // Sort by time descending
        backups.sort((a, b) -> Long.compare(b.getBackupTime(), a.getBackupTime()));
        
        return backups;
    }
    
    /**
     * Get a specific backup by ID.
     */
    public BackupData getBackup(String id) {
        return dataOperator.getById(id);
    }
    
    /**
     * Restore a backup to a player.
     */
    public boolean restoreBackup(Player player, BackupData backup) {
        if (backup == null) {
            return false;
        }
        
        // Clear current inventory
        player.getInventory().clear();
        
        // Restore inventory contents
        ItemStack[] contents = deserializeItems(backup.getInventoryContents());
        if (contents != null) {
            for (int i = 0; i < Math.min(contents.length, 36); i++) {
                if (contents[i] != null) {
                    player.getInventory().setItem(i, contents[i]);
                }
            }
        }
        
        // Restore armor
        if (config.isBackupArmor() && backup.getArmorContents() != null) {
            ItemStack[] armor = deserializeItems(backup.getArmorContents());
            if (armor != null) {
                player.getInventory().setArmorContents(armor);
            }
            ItemStack offhand = deserializeItem(backup.getOffhandItem());
            if (offhand != null) {
                player.getInventory().setItemInOffHand(offhand);
            }
        }
        
        // Restore ender chest
        if (config.isBackupEnderchest() && backup.getEnderchestContents() != null) {
            ItemStack[] enderChest = deserializeItems(backup.getEnderchestContents());
            if (enderChest != null) {
                player.getEnderChest().setContents(enderChest);
            }
        }
        
        // Restore exp
        if (config.isBackupExp()) {
            player.setLevel(backup.getExpLevel());
            player.setExp(backup.getExpProgress());
        }
        
        return true;
    }
    
    /**
     * Delete a backup.
     */
    public boolean deleteBackup(BackupData backup) {
        if (backup == null) {
            return false;
        }
        dataOperator.delById(backup.getId());
        return true;
    }
    
    /**
     * Delete a backup by ID.
     */
    public boolean deleteBackup(String id) {
        BackupData backup = dataOperator.getById(id);
        if (backup != null) {
            dataOperator.delById(id);
            return true;
        }
        return false;
    }
    
    /**
     * Auto backup all online players.
     */
    private void autoBackupAll() {
        Bukkit.getScheduler().runTask(UltiTools.getInstance(), () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("ultibackup.auto")) {
                    createBackup(player, "AUTO");
                }
            }
        });
    }
    
    /**
     * Clean up old backups for a player.
     */
    private void cleanupOldBackups(UUID playerUuid) {
        List<BackupData> backups = getBackups(playerUuid);
        
        if (backups.size() > config.getMaxBackupsPerPlayer()) {
            // Remove oldest backups
            for (int i = config.getMaxBackupsPerPlayer(); i < backups.size(); i++) {
                dataOperator.delById(backups.get(i).getId());
            }
        }
    }
    
    /**
     * Serialize items to YAML string.
     */
    private String serializeItems(ItemStack[] items) {
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
    private String serializeItem(ItemStack item) {
        if (item == null) return "";
        
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("item", item);
        return yaml.saveToString();
    }
    
    /**
     * Deserialize items from YAML string.
     */
    private ItemStack[] deserializeItems(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(data);
            
            List<ItemStack> items = new ArrayList<>();
            int maxSlot = 0;
            
            if (yaml.isConfigurationSection("items")) {
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
            }
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Deserialize single item from YAML string.
     */
    private ItemStack deserializeItem(String data) {
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
    
    public BackupConfig getConfig() {
        return config;
    }
}
