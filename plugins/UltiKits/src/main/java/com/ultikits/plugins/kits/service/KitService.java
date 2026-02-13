package com.ultikits.plugins.kits.service;

import com.ultikits.plugins.kits.model.KitDefinition;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;

/**
 * Service for managing gift package (kit) system.
 * 管理礼包系统的服务接口。
 */
public interface KitService {

    enum ClaimResult {
        SUCCESS, NOT_FOUND, NO_PERMISSION, INSUFFICIENT_LEVEL,
        INSUFFICIENT_FUNDS, ALREADY_CLAIMED, ON_COOLDOWN,
        INVENTORY_FULL, EMPTY_KIT, ERROR
    }

    enum CreateResult {
        SUCCESS, ALREADY_EXISTS, INVALID_NAME, EMPTY_INVENTORY, ERROR
    }

    void loadKits();

    void reload();

    @Nullable
    KitDefinition getKit(String name);

    Collection<KitDefinition> getAllKits();

    List<KitDefinition> getAvailableKits(Player player);

    List<String> getKitNames();

    CreateResult createKit(Player player, String name);

    boolean deleteKit(String name);

    boolean saveKitItems(String kitName, ItemStack[] items);

    ClaimResult claimKit(Player player, String kitName);

    long getRemainingCooldown(Player player, KitDefinition kit);

    String formatCooldown(long millis);

    String serializeItems(ItemStack[] items);

    @Nullable
    ItemStack[] deserializeItems(String data);
}
