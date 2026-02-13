package com.ultikits.plugins.remotebag.gui;

import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.entity.BagOpenResult;
import com.ultikits.plugins.remotebag.enums.AccessMode;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.plugins.remotebag.util.SoundUtil;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.gui.BaseInventoryPage;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.utils.XVersionUtils;
import mc.obliviate.inventory.Icon;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 远程背包内容 GUI
 * <p>
 * 显示和编辑背包的具体内容，支持编辑模式和只读模式。
 * <p>
 * Features:
 * <ul>
 *   <li>编辑模式：允许移动物品、保存</li>
 *   <li>只读模式：禁止移动物品、显示刷新按钮</li>
 *   <li>工具栏：返回、刷新、保存、模式指示、关闭按钮</li>
 *   <li>关闭时自动保存（编辑模式）并释放锁</li>
 * </ul>
 *
 * @author wisdomme
 * @version 1.0.0
 * @see BaseInventoryPage
 * @see RemoteBagService
 * @see BagLockService
 */
public class RemoteBagContentGUI extends BaseInventoryPage {

    private final UltiToolsPlugin plugin;
    private final RemoteBagService bagService;
    private final BagLockService lockService;
    private final RemoteBagConfig config;
    private final UUID ownerUuid;
    private final int pageNum;
    private final AccessMode accessMode;
    
    /**
     * 内容区域槽位数（前 5 行 = 45 槽）
     */
    private static final int CONTENT_SIZE = 45;
    
    /**
     * 创建远程背包内容 GUI
     *
     * @param viewer      查看者玩家
     * @param plugin      插件实例
     * @param ownerUuid   背包所有者 UUID
     * @param pageNum     背包页码
     * @param bagService  背包服务
     * @param lockService 锁定服务
     * @param config      配置
     * @param accessMode  访问模式
     */
    public RemoteBagContentGUI(@NotNull Player viewer,
                               UltiToolsPlugin plugin,
                               UUID ownerUuid,
                               int pageNum,
                               RemoteBagService bagService,
                               BagLockService lockService,
                               RemoteBagConfig config,
                               AccessMode accessMode) {
        super(viewer, "remotebag-content-" + pageNum, buildTitle(plugin, pageNum, accessMode), 6);
        this.plugin = plugin;
        this.ownerUuid = ownerUuid;
        this.pageNum = pageNum;
        this.bagService = bagService;
        this.lockService = lockService;
        this.config = config;
        this.accessMode = accessMode;
    }

    /**
     * 构建 GUI 标题
     *
     * @param plugin  插件实例
     * @param pageNum 页码
     * @param mode    访问模式
     * @return 格式化的标题
     */
    private static String buildTitle(UltiToolsPlugin plugin, int pageNum, AccessMode mode) {
        String base = plugin.i18n("bag_name").replace("{0}", String.valueOf(pageNum));
        if (mode == AccessMode.READ_ONLY) {
            return ChatColor.GRAY + "[" + plugin.i18n("read_only") + "] " + ChatColor.GOLD + base;
        }
        return ChatColor.GOLD + base;
    }
    
    /**
     * 设置 GUI 内容
     *
     * @param event 背包打开事件
     */
    @Override
    protected void setupContent(InventoryOpenEvent event) {
        // 加载背包内容到内容区域
        loadBagContents();
        
        // 设置工具栏
        setupToolbar();
    }
    
    /**
     * GUI 设置完成后的回调
     *
     * @param event 背包打开事件
     */
    @Override
    protected void afterSetup(InventoryOpenEvent event) {
        SoundUtil.playOpenSound(player, config);
    }
    
    /**
     * 加载背包内容到 GUI
     */
    private void loadBagContents() {
        // 确保背包数据已加载
        bagService.loadBagIfNeeded(ownerUuid);
        
        ItemStack[] contents = bagService.getBagPage(ownerUuid, pageNum);
        if (contents != null) {
            for (int i = 0; i < Math.min(contents.length, CONTENT_SIZE); i++) {
                if (contents[i] != null) {
                    // 物品直接放入，不设置 Icon 点击事件
                    // 编辑模式下允许自由移动，只读模式在 onClick 中处理
                    getInventory().setItem(i, contents[i]);
                }
            }
        }
    }
    
    /**
     * 设置工具栏按钮
     */
    private void setupToolbar() {
        // 清空底部工具栏
        // addToBottomRow 会自动放到最后一行
        
        // 返回按钮 (槽位 0)
        addToBottomRow(0, createBackButton());
        
        // 分隔 (槽位 1-2)
        addToBottomRow(1, createBackgroundIcon());
        addToBottomRow(2, createBackgroundIcon());
        
        // 刷新按钮 (槽位 3) - 仅只读模式显示实际按钮
        if (accessMode == AccessMode.READ_ONLY) {
            addToBottomRow(3, createRefreshButton());
        } else {
            addToBottomRow(3, createBackgroundIcon());
        }
        
        // 保存按钮 (槽位 4)
        if (accessMode == AccessMode.EDIT) {
            addToBottomRow(4, createSaveButton());
        } else {
            addToBottomRow(4, createDisabledSaveButton());
        }
        
        // 模式指示器 (槽位 5)
        addToBottomRow(5, createModeIndicator());
        
        // 分隔 (槽位 6-7)
        addToBottomRow(6, createBackgroundIcon());
        addToBottomRow(7, createBackgroundIcon());
        
        // 关闭按钮 (槽位 8)
        addToBottomRow(8, createCloseButton());
    }
    
    /**
     * 创建返回按钮
     *
     * @return 返回按钮 Icon
     */
    private Icon createBackButton() {
        Icon icon = createActionButton(Colors.YELLOW, ChatColor.YELLOW + plugin.i18n("btn_back"), e -> {
            SoundUtil.playPageSound(player, config);
            // 先关闭当前 GUI（会触发 onClose 保存）
            player.closeInventory();
            // 返回主页
            new RemoteBagMainGUI(player, plugin, bagService, lockService, config).open();
        });
        
        // 设置 lore
        ItemStack item = icon.getItem();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GRAY + plugin.i18n("lore_back_to_main"));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        
        return icon;
    }
    
    /**
     * 创建刷新按钮（只读模式专用）
     *
     * @return 刷新按钮 Icon
     */
    private Icon createRefreshButton() {
        ItemStack item = new ItemStack(Material.SUNFLOWER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + plugin.i18n("btn_refresh"));
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GRAY + plugin.i18n("lore_refresh_hint1"));
            lore.add(ChatColor.GRAY + plugin.i18n("lore_refresh_hint2"));
            lore.add(ChatColor.GRAY + plugin.i18n("lore_refresh_hint3"));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        
        Icon icon = new Icon(item);
        icon.onClick(e -> {
            // 检查是否可以升级为编辑模式
            if (lockService.canUpgradeToEdit(ownerUuid, pageNum)) {
                player.sendMessage(ChatColor.GREEN + plugin.i18n("msg_upgrading_to_edit"));
                player.closeInventory();
                
                // 重新以编辑模式打开
                BagOpenResult result = lockService.adminOpen(ownerUuid, pageNum, player);
                if (result.isEditMode()) {
                    new RemoteBagContentGUI(player, plugin, ownerUuid, pageNum,
                            bagService, lockService, config, AccessMode.EDIT).open();
                } else {
                    // 如果还是无法获取编辑权限，以只读模式重新打开
                    new RemoteBagContentGUI(player, plugin, ownerUuid, pageNum,
                            bagService, lockService, config, AccessMode.READ_ONLY).open();
                }
            } else {
                SoundUtil.playErrorSound(player, config);
                player.sendMessage(ChatColor.YELLOW + plugin.i18n("msg_owner_still_using"));
                // 刷新内容显示
                loadBagContents();
            }
        });
        
        return icon;
    }
    
    /**
     * 创建保存按钮（编辑模式）
     *
     * @return 保存按钮 Icon
     */
    private Icon createSaveButton() {
        Icon icon = createActionButton(Colors.GREEN, ChatColor.GREEN + plugin.i18n("btn_save"), e -> {
            saveCurrentContents();
            SoundUtil.playCloseSound(player, config);
            player.sendMessage(ChatColor.GREEN + plugin.i18n("msg_bag_saved"));
        });
        
        // 设置 lore
        ItemStack item = icon.getItem();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GRAY + plugin.i18n("lore_save_hint"));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        
        return icon;
    }
    
    /**
     * 创建禁用的保存按钮（只读模式）
     *
     * @return 禁用的保存按钮 Icon
     */
    private Icon createDisabledSaveButton() {
        ItemStack item = XVersionUtils.getColoredPlaneGlass(Colors.RED);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + plugin.i18n("btn_save_disabled"));
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GRAY + plugin.i18n("lore_readonly_hint1"));
            lore.add(ChatColor.GRAY + plugin.i18n("lore_readonly_hint2"));
            lore.add("");
            lore.add(ChatColor.YELLOW + plugin.i18n("lore_readonly_hint3"));
            lore.add(ChatColor.YELLOW + plugin.i18n("lore_readonly_hint4"));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        
        Icon icon = new Icon(item);
        icon.onClick(e -> {
            SoundUtil.playErrorSound(player, config);
            player.sendMessage(ChatColor.RED + plugin.i18n("msg_cannot_save_readonly"));
        });
        
        return icon;
    }
    
    /**
     * 创建模式指示器
     *
     * @return 模式指示器 Icon
     */
    private Icon createModeIndicator() {
        Colors color;
        String name;
        List<String> lore = new ArrayList<>();
        
        if (accessMode == AccessMode.EDIT) {
            color = Colors.GREEN;
            name = ChatColor.GREEN + plugin.i18n("mode_edit");
            lore.add("");
            lore.add(ChatColor.GRAY + plugin.i18n("lore_edit_mode"));
        } else {
            color = Colors.YELLOW;
            name = ChatColor.YELLOW + plugin.i18n("mode_readonly");
            lore.add("");
            lore.add(ChatColor.GRAY + plugin.i18n("lore_readonly_mode1"));
            lore.add(ChatColor.GRAY + plugin.i18n("lore_readonly_mode2"));
        }
        
        ItemStack item = XVersionUtils.getColoredPlaneGlass(color);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        
        return new Icon(item);
    }
    
    /**
     * 创建关闭按钮
     *
     * @return 关闭按钮 Icon
     */
    private Icon createCloseButton() {
        Icon icon = createActionButton(Colors.RED, ChatColor.RED + plugin.i18n("btn_close"), e -> {
            player.closeInventory();
        });
        
        // 设置 lore
        ItemStack item = icon.getItem();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add("");
            if (accessMode == AccessMode.EDIT) {
                lore.add(ChatColor.GRAY + plugin.i18n("lore_close_save"));
            } else {
                lore.add(ChatColor.GRAY + plugin.i18n("lore_close_discard"));
            }
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        
        return icon;
    }
    
    /**
     * 处理物品点击事件
     *
     * @param event 点击事件
     * @return true 取消事件，false 允许事件
     */
    @Override
    public boolean onClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        
        // 工具栏区域 - 让 Icon 的点击事件处理
        if (slot >= CONTENT_SIZE) {
            return true; // 取消默认行为，由 Icon onClick 处理
        }
        
        // 内容区域
        if (accessMode == AccessMode.READ_ONLY) {
            // 只读模式 - 禁止所有物品操作
            if (event.getCurrentItem() != null || event.getCursor() != null) {
                SoundUtil.playErrorSound(player, config);
                player.sendMessage(ChatColor.RED + plugin.i18n("msg_readonly_no_move"));
            }
            return true; // 取消事件
        }
        
        // 编辑模式 - 允许物品移动
        return false; // 不取消事件
    }
    
    /**
     * 处理 GUI 关闭事件
     *
     * @param event 关闭事件
     */
    @Override
    public void onClose(InventoryCloseEvent event) {
        if (accessMode == AccessMode.EDIT) {
            // 编辑模式 - 保存并释放锁
            saveCurrentContents();
            lockService.release(ownerUuid, pageNum, player.getUniqueId());
            SoundUtil.playCloseSound(player, config);
        } else {
            // 只读模式 - 仅释放只读会话
            lockService.release(ownerUuid, pageNum, player.getUniqueId());
        }
    }
    
    /**
     * 保存当前 GUI 中的内容到背包服务
     */
    private void saveCurrentContents() {
        ItemStack[] contents = new ItemStack[CONTENT_SIZE];
        for (int i = 0; i < CONTENT_SIZE; i++) {
            contents[i] = getInventory().getItem(i);
        }
        bagService.setBagPage(ownerUuid, pageNum, contents);
        bagService.saveBag(ownerUuid);
    }
}
