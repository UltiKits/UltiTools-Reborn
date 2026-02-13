package com.ultikits.plugins.kits.service;

import com.ultikits.plugins.kits.entity.KitClaimData;
import com.ultikits.plugins.kits.model.KitDefinition;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.utils.EconomyUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Implementation of KitService.
 * 礼包服务实现。
 */
@Service
public class KitServiceImpl implements KitService {

    private final UltiToolsPlugin plugin;
    private final PluginLogger logger;
    private final Map<String, KitDefinition> kits = new LinkedHashMap<>();
    private DataOperator<KitClaimData> claimOperator;

    public KitServiceImpl(UltiToolsPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.claimOperator = plugin.getDataOperator(KitClaimData.class);
        loadKits();
    }

    @Override
    public void loadKits() {
        kits.clear();

        File kitsFolder = new File(plugin.getResourceFolderPath(), "kits");
        if (!kitsFolder.exists()) {
            kitsFolder.mkdirs();
            copyExampleKit(kitsFolder);
        }

        File[] files = kitsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            logger.warn(plugin.i18n("没有找到礼包配置文件"));
            return;
        }

        int loadedCount = 0;
        for (File file : files) {
            KitDefinition kit = parseKitFile(file);
            if (kit != null) {
                String kitName = file.getName().replace(".yml", "").toLowerCase();
                kit.setName(kitName);
                kits.put(kitName, kit);
                loadedCount++;
            }
        }

        logger.info(String.format(plugin.i18n("共加载 %d 个礼包"), loadedCount));
    }

    @Override
    public void reload() {
        loadKits();
    }

    @Nullable
    @Override
    public KitDefinition getKit(String name) {
        return kits.get(name.toLowerCase());
    }

    @Override
    public Collection<KitDefinition> getAllKits() {
        return Collections.unmodifiableCollection(kits.values());
    }

    @Override
    public List<KitDefinition> getAvailableKits(Player player) {
        return kits.values().stream()
                .filter(kit -> !kit.hasPermission() || player.hasPermission(kit.getPermission()))
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getKitNames() {
        return new ArrayList<>(kits.keySet());
    }

    @Override
    public CreateResult createKit(Player player, String name) {
        String normalizedName = name.toLowerCase().trim();
        if (normalizedName.isEmpty() || normalizedName.length() > 32) {
            return CreateResult.INVALID_NAME;
        }

        if (kits.containsKey(normalizedName)) {
            return CreateResult.ALREADY_EXISTS;
        }

        // Filter out air and null items from player inventory
        ItemStack[] validItems = Arrays.stream(player.getInventory().getStorageContents())
                .filter(item -> item != null && item.getType() != Material.AIR)
                .toArray(ItemStack[]::new);

        if (validItems.length == 0) {
            return CreateResult.EMPTY_INVENTORY;
        }

        String serializedItems = serializeItems(validItems);
        if (serializedItems == null) {
            return CreateResult.ERROR;
        }

        // Create kit definition
        KitDefinition kit = new KitDefinition();
        kit.setName(normalizedName);
        kit.setDisplayName("&f" + name);
        kit.setIcon(validItems[0].getType().name());
        kit.setItems(serializedItems);

        // Save to YAML
        if (!saveKitToFile(normalizedName, kit)) {
            return CreateResult.ERROR;
        }

        kits.put(normalizedName, kit);
        return CreateResult.SUCCESS;
    }

    @Override
    public boolean deleteKit(String name) {
        String normalizedName = name.toLowerCase().trim();
        if (!kits.containsKey(normalizedName)) {
            return false;
        }

        File kitFile = new File(plugin.getResourceFolderPath(), "kits/" + normalizedName + ".yml");
        if (kitFile.exists()) {
            kitFile.delete(); // NOPMD
        }

        kits.remove(normalizedName);
        return true;
    }

    @Override
    public boolean saveKitItems(String kitName, ItemStack[] items) {
        KitDefinition kit = getKit(kitName);
        if (kit == null) {
            return false;
        }

        // Filter out null/air items
        ItemStack[] validItems = Arrays.stream(items)
                .filter(item -> item != null && item.getType() != Material.AIR)
                .toArray(ItemStack[]::new);

        String serialized = serializeItems(validItems);
        if (serialized == null) {
            return false;
        }

        kit.setItems(serialized);
        return saveKitToFile(kit.getName(), kit);
    }

    @Override
    public ClaimResult claimKit(Player player, String kitName) {
        KitDefinition kit = getKit(kitName);
        if (kit == null) {
            return ClaimResult.NOT_FOUND;
        }

        ClaimResult validationResult = validateClaim(player, kit);
        if (validationResult != null) {
            return validationResult;
        }

        ItemStack[] items = deserializeItems(kit.getItems());
        if (items == null || items.length == 0) {
            return ClaimResult.EMPTY_KIT;
        }

        if (countEmptySlots(player) < items.length) {
            return ClaimResult.INVENTORY_FULL;
        }

        deliverKit(player, kit, items);
        return ClaimResult.SUCCESS;
    }

    /**
     * Validates player eligibility to claim a kit.
     * Returns null if all checks pass, or the failure result.
     */
    @Nullable
    ClaimResult validateClaim(Player player, KitDefinition kit) {
        ClaimResult prereq = checkPrerequisites(player, kit);
        if (prereq != null) {
            return prereq;
        }
        ClaimResult cooldown = checkCooldown(player, kit);
        if (cooldown != null) {
            return cooldown;
        }
        return kit.hasItems() ? null : ClaimResult.EMPTY_KIT;
    }

    @Nullable
    private ClaimResult checkPrerequisites(Player player, KitDefinition kit) {
        if (kit.hasPermission() && !player.hasPermission(kit.getPermission())) {
            return ClaimResult.NO_PERMISSION;
        }
        if (kit.hasLevelRequirement() && player.getLevel() < kit.getLevelRequired()) {
            return ClaimResult.INSUFFICIENT_LEVEL;
        }
        if (!kit.isFree() && !canAfford(player, kit.getPrice())) {
            return ClaimResult.INSUFFICIENT_FUNDS;
        }
        return null;
    }

    private boolean canAfford(Player player, double price) {
        return EconomyUtils.isAvailable() && EconomyUtils.has(player, price);
    }

    @Nullable
    private ClaimResult checkCooldown(Player player, KitDefinition kit) {
        KitClaimData claim = getClaimData(player.getUniqueId(), kit.getName());
        if (claim == null) {
            return null;
        }
        if (kit.isOneTime()) {
            return ClaimResult.ALREADY_CLAIMED;
        }
        return getRemainingCooldown(player, kit) > 0 ? ClaimResult.ON_COOLDOWN : null;
    }

    private int countEmptySlots(Player player) {
        int count = 0;
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType() == Material.AIR) {
                count++;
            }
        }
        return count;
    }

    private void deliverKit(Player player, KitDefinition kit, ItemStack[] items) {
        if (!kit.isFree() && EconomyUtils.isAvailable()) {
            EconomyUtils.withdraw(player, kit.getPrice());
        }
        for (ItemStack item : items) {
            player.getInventory().addItem(item.clone());
        }
        executePlayerCommands(player, kit.getPlayerCommands());
        executeConsoleCommands(player, kit.getConsoleCommands());
        updateClaimData(player.getUniqueId(), kit.getName());
    }

    @Override
    public long getRemainingCooldown(Player player, KitDefinition kit) {
        if (kit.isOneTime()) {
            KitClaimData claim = getClaimData(player.getUniqueId(), kit.getName());
            return claim != null ? -1 : 0;
        }

        KitClaimData claim = getClaimData(player.getUniqueId(), kit.getName());
        if (claim == null) {
            return 0;
        }

        long cooldownEnd = claim.getLastClaim() + (kit.getCooldown() * 1000);
        long remaining = cooldownEnd - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    @Override
    public String formatCooldown(long millis) {
        if (millis <= 0) {
            return plugin.i18n("可领取");
        }

        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;

        StringBuilder sb = new StringBuilder();
        if (hours > 0) {
            sb.append(String.format(plugin.i18n("%d小时"), hours)).append(" ");
        }
        if (minutes > 0) {
            sb.append(String.format(plugin.i18n("%d分钟"), minutes)).append(" ");
        }
        if (seconds > 0 || sb.length() == 0) {
            sb.append(String.format(plugin.i18n("%d秒"), seconds));
        }

        return sb.toString().trim();
    }

    @Override
    public String serializeItems(ItemStack[] items) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

            dataOutput.writeInt(items.length);
            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }

            dataOutput.close();
            return Base64Coder.encodeLines(outputStream.toByteArray());
        } catch (IOException e) {
            logger.error("Failed to serialize kit items: " + e.getMessage());
            return null;
        }
    }

    @Nullable
    @Override
    public ItemStack[] deserializeItems(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);

            int size = dataInput.readInt();
            ItemStack[] items = new ItemStack[size];

            for (int i = 0; i < size; i++) {
                items[i] = (ItemStack) dataInput.readObject();
            }

            dataInput.close();
            return items;
        } catch (IOException | ClassNotFoundException e) {
            logger.error("Failed to deserialize kit items: " + e.getMessage());
            return null;
        }
    }

    // --- Internal methods ---

    @Nullable
    KitClaimData getClaimData(UUID playerUuid, String kitName) {
        List<KitClaimData> claims = claimOperator.query()
                .where("player_uuid").eq(playerUuid.toString())
                .list();

        return claims.stream()
                .filter(c -> c.getKitName().equalsIgnoreCase(kitName))
                .findFirst()
                .orElse(null);
    }

    void updateClaimData(UUID playerUuid, String kitName) {
        KitClaimData existing = getClaimData(playerUuid, kitName);

        if (existing != null) {
            existing.setLastClaim(System.currentTimeMillis());
            existing.setClaimCount(existing.getClaimCount() + 1);
            try {
                claimOperator.update(existing);
            } catch (IllegalAccessException e) {
                logger.error("Failed to update kit claim data: " + e.getMessage());
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

    @Nullable
    KitDefinition parseKitFile(File file) {
        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

            KitDefinition kit = new KitDefinition();
            kit.setDisplayName(config.getString("displayName", "&7Kit"));
            kit.setDescription(config.getStringList("description"));
            kit.setPrice(config.getDouble("price", 0));
            kit.setLevelRequired(config.getInt("levelRequired", 0));
            kit.setPermission(config.getString("permission", ""));
            kit.setReBuyable(config.getBoolean("reBuyable", false));
            kit.setCooldown(config.getLong("cooldown", 0));
            kit.setPlayerCommands(config.getStringList("playerCommands"));
            kit.setConsoleCommands(config.getStringList("consoleCommands"));
            kit.setItems(config.getString("items", ""));

            // Validate icon material
            String iconStr = config.getString("icon", "CHEST");
            try {
                Material.valueOf(iconStr.toUpperCase());
                kit.setIcon(iconStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                logger.warn(plugin.i18n("加载礼包失败: ") + file.getName() + " - invalid icon: " + iconStr);
                kit.setIcon("CHEST");
            }

            return kit;
        } catch (Exception e) {
            logger.warn(plugin.i18n("加载礼包失败: ") + file.getName() + " - " + e.getMessage());
            return null;
        }
    }

    boolean saveKitToFile(String name, KitDefinition kit) {
        try {
            File kitFile = new File(plugin.getResourceFolderPath(), "kits/" + name + ".yml");
            YamlConfiguration config = new YamlConfiguration();

            config.set("displayName", kit.getDisplayName());
            config.set("description", kit.getDescription());
            config.set("icon", kit.getIcon());
            config.set("price", kit.getPrice());
            config.set("levelRequired", kit.getLevelRequired());
            config.set("permission", kit.getPermission());
            config.set("reBuyable", kit.isReBuyable());
            config.set("cooldown", kit.getCooldown());
            config.set("playerCommands", kit.getPlayerCommands());
            config.set("consoleCommands", kit.getConsoleCommands());
            config.set("items", kit.getItems());

            config.save(kitFile);
            return true;
        } catch (IOException e) {
            logger.error("Failed to save kit file: " + name + " - " + e.getMessage());
            return false;
        }
    }

    private void copyExampleKit(File folder) {
        try (InputStream is = plugin.getClass().getClassLoader().getResourceAsStream("kits/starter.yml")) {
            File exampleFile = new File(folder, "starter.yml");
            if (is != null && !exampleFile.exists()) {
                Files.copy(is, exampleFile.toPath());
            }
        } catch (IOException e) {
            logger.warn("Failed to copy example kit: " + e.getMessage());
        }
    }

    private void executePlayerCommands(Player player, List<String> commands) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        for (String cmd : commands) {
            String processed = cmd.replace("{player}", player.getName());
            player.performCommand(processed);
        }
    }

    private void executeConsoleCommands(Player player, List<String> commands) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        org.bukkit.plugin.Plugin ultiToolsPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");
        if (ultiToolsPlugin == null) {
            return;
        }
        for (String cmd : commands) {
            String processed = cmd.replace("{player}", player.getName());
            String finalCmd = processed;
            Bukkit.getScheduler().runTask(ultiToolsPlugin, () ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd));
        }
    }
}
