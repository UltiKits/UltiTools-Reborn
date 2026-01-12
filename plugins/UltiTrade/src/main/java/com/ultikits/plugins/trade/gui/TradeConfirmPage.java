package com.ultikits.plugins.trade.gui;

import com.ultikits.plugins.trade.entity.TradeSession;
import com.ultikits.plugins.trade.service.TradeService;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Trade confirmation page for large trades.
 * Shows summary of the trade and requires explicit confirmation.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class TradeConfirmPage implements InventoryHolder {
    
    private final TradeService tradeService;
    private final TradeSession session;
    private final Player viewer;
    private final Inventory inventory;
    private final Runnable onConfirm;
    private final Runnable onCancel;
    
    // GUI layout
    public static final int ROWS = 5;
    public static final int SIZE = ROWS * 9;
    
    // Button positions
    public static final int CONFIRM_SLOT = 38;
    public static final int CANCEL_SLOT = 42;
    public static final int INFO_SLOT = 13;
    
    // Display positions
    public static final int YOUR_ITEMS_START = 10;
    public static final int THEIR_ITEMS_START = 14;
    public static final int YOUR_MONEY_SLOT = 28;
    public static final int YOUR_EXP_SLOT = 29;
    public static final int THEIR_MONEY_SLOT = 32;
    public static final int THEIR_EXP_SLOT = 33;
    
    public TradeConfirmPage(TradeService tradeService, TradeSession session, Player viewer,
                            Runnable onConfirm, Runnable onCancel) {
        this.tradeService = tradeService;
        this.session = session;
        this.viewer = viewer;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        
        Player other = Bukkit.getPlayer(session.getOtherPlayer(viewer.getUniqueId()));
        String title = ChatColor.GOLD + "确认与 " + (other != null ? other.getName() : "???") + " 的交易";
        
        this.inventory = Bukkit.createInventory(this, SIZE, title);
        initializeGUI();
    }
    
    /**
     * Initialize the GUI.
     */
    private void initializeGUI() {
        UUID viewerUuid = viewer.getUniqueId();
        Player other = Bukkit.getPlayer(session.getOtherPlayer(viewerUuid));
        
        // Fill background
        ItemStack background = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, background);
        }
        
        // Info item
        double yourMoney = session.getPlayerMoney(viewerUuid);
        double theirMoney = session.getOtherPlayerMoney(viewerUuid);
        int yourExp = session.getPlayerExp(viewerUuid);
        int theirExp = session.getOtherPlayerExp(viewerUuid);
        double taxRate = tradeService.getConfig().getTradeTax();
        double expTaxRate = tradeService.getConfig().getExpTaxRate();
        
        double threshold = tradeService.getConfig().getConfirmThreshold();
        
        List<String> infoLore = new ArrayList<>();
        infoLore.add("");
        infoLore.add(ChatColor.YELLOW + "此交易需要二次确认！");
        infoLore.add(ChatColor.GRAY + "(金币或经验超过 " + (int)threshold + ")");
        infoLore.add("");
        infoLore.add(ChatColor.GREEN + "你将给出:");
        infoLore.add(ChatColor.WHITE + "  金币: " + ChatColor.GOLD + String.format("%.2f", yourMoney));
        if (taxRate > 0 && yourMoney > 0) {
            infoLore.add(ChatColor.RED + "    税: -" + String.format("%.2f", yourMoney * taxRate));
        }
        infoLore.add(ChatColor.WHITE + "  经验: " + ChatColor.GREEN + yourExp);
        if (expTaxRate > 0 && yourExp > 0) {
            infoLore.add(ChatColor.RED + "    税: -" + (int)(yourExp * expTaxRate));
        }
        infoLore.add(ChatColor.WHITE + "  物品: " + session.getPlayerItems(viewerUuid).size() + " 个");
        infoLore.add("");
        infoLore.add(ChatColor.AQUA + "你将收到:");
        infoLore.add(ChatColor.WHITE + "  金币: " + ChatColor.GOLD + String.format("%.2f", theirMoney * (1 - taxRate)));
        infoLore.add(ChatColor.WHITE + "  经验: " + ChatColor.GREEN + (int)(theirExp * (1 - expTaxRate)));
        infoLore.add(ChatColor.WHITE + "  物品: " + session.getOtherPlayerItems(viewerUuid).size() + " 个");
        infoLore.add("");
        infoLore.add(ChatColor.YELLOW + "请仔细确认交易内容！");
        
        ItemStack infoItem = createItem(Material.PAPER, ChatColor.GOLD + "⚠ 交易确认", infoLore);
        inventory.setItem(INFO_SLOT, infoItem);
        
        // Display your items (3 slots)
        displayItems(session.getPlayerItems(viewerUuid), YOUR_ITEMS_START, ChatColor.GREEN + "你的物品");
        
        // Display their items (3 slots)
        displayItems(session.getOtherPlayerItems(viewerUuid), THEIR_ITEMS_START, ChatColor.AQUA + "对方物品");
        
        // Money display
        ItemStack yourMoneyItem = createItem(Material.GOLD_INGOT, 
            ChatColor.GOLD + "你给出的金币",
            Arrays.asList(
                ChatColor.WHITE + "金额: " + ChatColor.YELLOW + String.format("%.2f", yourMoney),
                taxRate > 0 ? ChatColor.RED + "税后对方收到: " + String.format("%.2f", yourMoney * (1 - taxRate)) : ""
            ));
        inventory.setItem(YOUR_MONEY_SLOT, yourMoneyItem);
        
        ItemStack theirMoneyItem = createItem(Material.GOLD_INGOT,
            ChatColor.GOLD + "对方给出的金币",
            Arrays.asList(
                ChatColor.WHITE + "金额: " + ChatColor.YELLOW + String.format("%.2f", theirMoney),
                taxRate > 0 ? ChatColor.GREEN + "你将收到: " + String.format("%.2f", theirMoney * (1 - taxRate)) : ""
            ));
        inventory.setItem(THEIR_MONEY_SLOT, theirMoneyItem);
        
        // Exp display
        ItemStack yourExpItem = createItem(Material.EXPERIENCE_BOTTLE,
            ChatColor.GREEN + "你给出的经验",
            Arrays.asList(
                ChatColor.WHITE + "经验: " + ChatColor.GREEN + yourExp,
                expTaxRate > 0 ? ChatColor.RED + "税后对方收到: " + (int)(yourExp * (1 - expTaxRate)) : ""
            ));
        inventory.setItem(YOUR_EXP_SLOT, yourExpItem);
        
        ItemStack theirExpItem = createItem(Material.EXPERIENCE_BOTTLE,
            ChatColor.GREEN + "对方给出的经验",
            Arrays.asList(
                ChatColor.WHITE + "经验: " + ChatColor.GREEN + theirExp,
                expTaxRate > 0 ? ChatColor.GREEN + "你将收到: " + (int)(theirExp * (1 - expTaxRate)) : ""
            ));
        inventory.setItem(THEIR_EXP_SLOT, theirExpItem);
        
        // Confirm button
        ItemStack confirmBtn = createItem(Material.LIME_CONCRETE,
            ChatColor.GREEN + "✔ 确认交易",
            Arrays.asList(
                ChatColor.GRAY + "点击确认此交易",
                "",
                ChatColor.YELLOW + "确认后交易将立即完成！"
            ));
        inventory.setItem(CONFIRM_SLOT, confirmBtn);
        
        // Cancel button
        ItemStack cancelBtn = createItem(Material.RED_CONCRETE,
            ChatColor.RED + "✖ 取消",
            Arrays.asList(
                ChatColor.GRAY + "点击返回交易界面",
                "",
                ChatColor.YELLOW + "不会取消交易"
            ));
        inventory.setItem(CANCEL_SLOT, cancelBtn);
    }
    
    /**
     * Display items in the GUI.
     */
    private void displayItems(Map<Integer, ItemStack> items, int startSlot, String emptyName) {
        int displaySlots = 3;
        List<ItemStack> itemList = new ArrayList<>(items.values());
        
        for (int i = 0; i < displaySlots; i++) {
            if (i < itemList.size()) {
                ItemStack item = itemList.get(i).clone();
                // Add info to lore
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                    lore.add("");
                    lore.add(ChatColor.DARK_GRAY + "---交易物品---");
                    item.setItemMeta(meta);
                }
                inventory.setItem(startSlot + i, item);
            } else {
                ItemStack empty = createItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, emptyName);
                inventory.setItem(startSlot + i, empty);
            }
        }
        
        // If more than 3 items, show count
        if (itemList.size() > displaySlots) {
            ItemStack moreItem = createItem(Material.CHEST,
                ChatColor.YELLOW + "还有 " + (itemList.size() - displaySlots) + " 个物品...",
                Arrays.asList(ChatColor.GRAY + "打开交易窗口查看所有物品"));
            inventory.setItem(startSlot + displaySlots - 1, moreItem);
        }
    }
    
    /**
     * Handle click event.
     */
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        
        int slot = event.getRawSlot();
        
        if (slot == CONFIRM_SLOT) {
            viewer.closeInventory();
            if (onConfirm != null) {
                onConfirm.run();
            }
        } else if (slot == CANCEL_SLOT) {
            viewer.closeInventory();
            if (onCancel != null) {
                onCancel.run();
            }
        }
    }
    
    /**
     * Create an item with name and lore.
     */
    private ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && !lore.isEmpty()) {
                // Filter out empty strings
                List<String> filteredLore = new ArrayList<>();
                for (String line : lore) {
                    if (line != null && !line.isEmpty()) {
                        filteredLore.add(line);
                    }
                }
                meta.setLore(filteredLore);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }
    
    /**
     * Create an item with name only.
     */
    private ItemStack createItem(Material material, String name) {
        return createItem(material, name, null);
    }
    
    /**
     * Open the confirm page for a player.
     */
    public void open() {
        viewer.openInventory(inventory);
    }
    
    @Override
    public Inventory getInventory() {
        return inventory;
    }
    
    public Player getViewer() {
        return viewer;
    }
}
