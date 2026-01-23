# GUI 系统

本文档详细介绍 UltiTools-API 的背包 GUI 开发系统。

---

## 目录

- [概述](#概述)
- [基础页面](#基础页面)
- [分页功能](#分页功能)
- [确认对话框](#确认对话框)
- [图标与交互](#图标与交互)
- [完整示例](#完整示例)

---

## 概述

UltiTools GUI 系统基于 [ObliviateInvs](https://github.com/hamza-cskn/obliviate-invs) 库，提供模板方法模式的页面基类：

| 基类 | 用途 |
|------|------|
| `BaseInventoryPage` | 通用背包页面 |
| `BasePaginationPage` | 分页列表页面 |
| `BaseConfirmationPage` | 确认/取消对话框 |

**特点**:

- 声明式页面定义
- 内置分页逻辑
- 统一的底部工具栏
- 支持点击事件处理

---

## 基础页面

### BaseInventoryPage

创建自定义背包页面：

```java
import com.ultikits.ultitools.abstracts.gui.BaseInventoryPage;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import mc.obliviate.inventory.Icon;

public class MyMenuPage extends BaseInventoryPage {
    
    public MyMenuPage(Player player) {
        super(player, "my-menu", "我的菜单", 3); // 3 行 = 27 格
    }
    
    @Override
    protected void setupContent(InventoryOpenEvent event) {
        // 设置菜单项
        addItem(10, createMenuIcon("选项 1", Material.DIAMOND, () -> {
            player.sendMessage("你点击了选项 1");
        }));
        
        addItem(12, createMenuIcon("选项 2", Material.GOLD_INGOT, () -> {
            player.sendMessage("你点击了选项 2");
        }));
        
        addItem(14, createMenuIcon("选项 3", Material.IRON_INGOT, () -> {
            player.sendMessage("你点击了选项 3");
        }));
        
        addItem(16, createMenuIcon("关闭", Material.BARRIER, () -> {
            player.closeInventory();
        }));
    }
    
    private Icon createMenuIcon(String name, Material material, Runnable onClick) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + name);
        item.setItemMeta(meta);
        
        return new Icon(item).onClick(e -> {
            e.setCancelled(true);
            onClick.run();
        });
    }
}
```

### 打开页面

```java
// 在命令或其他地方
Player player = ...;
new MyMenuPage(player).open();
```

### 生命周期钩子

```java
public class MyPage extends BaseInventoryPage {
    
    @Override
    protected void setupContent(InventoryOpenEvent event) {
        // 页面打开时调用，设置内容
    }
    
    @Override
    protected void afterSetup(InventoryOpenEvent event) {
        // setupContent 之后调用
    }
    
    @Override
    public boolean onClick(InventoryClickEvent event) {
        // 处理点击事件，返回 true 取消默认行为
        return true;
    }
    
    @Override
    public void onClose(InventoryCloseEvent event) {
        // 页面关闭时调用
    }
}
```

---

## 分页功能

### BasePaginationPage

用于显示列表数据的分页页面：

```java
import com.ultikits.ultitools.abstracts.gui.BasePaginationPage;

public class PlayerListPage extends BasePaginationPage<OfflinePlayer> {
    
    public PlayerListPage(Player viewer) {
        super(viewer, "player-list", "玩家列表", 6); // 6 行
    }
    
    @Override
    protected List<OfflinePlayer> getDataList() {
        // 返回要分页显示的数据列表
        return Arrays.asList(Bukkit.getOfflinePlayers());
    }
    
    @Override
    protected Icon createItemIcon(OfflinePlayer player, int index) {
        // 为每个数据项创建图标
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(player);
        meta.setDisplayName(ChatColor.YELLOW + player.getName());
        
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "点击查看详情");
        if (player.isOnline()) {
            lore.add(ChatColor.GREEN + "在线");
        } else {
            lore.add(ChatColor.RED + "离线");
        }
        meta.setLore(lore);
        head.setItemMeta(meta);
        
        return new Icon(head).onClick(e -> {
            e.setCancelled(true);
            viewer.sendMessage("你选择了: " + player.getName());
            viewer.closeInventory();
        });
    }
    
    @Override
    protected int[] getContentSlots() {
        // 返回用于显示内容的槽位
        // 默认使用中间区域，排除边框
        return new int[]{
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
        };
    }
}
```

### 分页控件

分页页面自动添加翻页按钮：

| 位置 | 按钮 | 功能 |
|------|------|------|
| 底部左侧 | ◀ 上一页 | 翻到上一页 |
| 底部右侧 | 下一页 ▶ | 翻到下一页 |
| 底部中间 | 页码显示 | 显示当前页/总页数 |

### 自定义分页按钮

```java
@Override
protected Icon getPreviousPageIcon() {
    ItemStack item = new ItemStack(Material.ARROW);
    ItemMeta meta = item.getItemMeta();
    meta.setDisplayName(ChatColor.YELLOW + "上一页");
    item.setItemMeta(meta);
    return new Icon(item);
}

@Override
protected Icon getNextPageIcon() {
    ItemStack item = new ItemStack(Material.ARROW);
    ItemMeta meta = item.getItemMeta();
    meta.setDisplayName(ChatColor.YELLOW + "下一页");
    item.setItemMeta(meta);
    return new Icon(item);
}
```

---

## 确认对话框

### BaseConfirmationPage

用于确认/取消操作的对话框：

```java
import com.ultikits.ultitools.abstracts.gui.BaseConfirmationPage;

public class DeleteConfirmPage extends BaseConfirmationPage {
    
    private final String itemName;
    private final Runnable onConfirm;
    
    public DeleteConfirmPage(Player player, String itemName, Runnable onConfirm) {
        super(player, "delete-confirm", "确认删除");
        this.itemName = itemName;
        this.onConfirm = onConfirm;
    }
    
    @Override
    protected Icon getConfirmIcon() {
        ItemStack item = new ItemStack(Material.GREEN_WOOL);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GREEN + "确认删除");
        meta.setLore(Arrays.asList(
            ChatColor.GRAY + "删除: " + itemName,
            ChatColor.RED + "此操作不可撤销！"
        ));
        item.setItemMeta(meta);
        
        return new Icon(item).onClick(e -> {
            e.setCancelled(true);
            player.closeInventory();
            onConfirm.run();
            player.sendMessage(ChatColor.GREEN + "已删除: " + itemName);
        });
    }
    
    @Override
    protected Icon getCancelIcon() {
        ItemStack item = new ItemStack(Material.RED_WOOL);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.RED + "取消");
        item.setItemMeta(meta);
        
        return new Icon(item).onClick(e -> {
            e.setCancelled(true);
            player.closeInventory();
            player.sendMessage(ChatColor.YELLOW + "已取消操作");
        });
    }
    
    @Override
    protected Icon getInfoIcon() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + "删除确认");
        meta.setLore(Arrays.asList(
            ChatColor.WHITE + "你确定要删除 " + ChatColor.RED + itemName + ChatColor.WHITE + " 吗？",
            "",
            ChatColor.GRAY + "点击绿色按钮确认",
            ChatColor.GRAY + "点击红色按钮取消"
        ));
        item.setItemMeta(meta);
        return new Icon(item);
    }
}
```

### 使用确认对话框

```java
// 删除前确认
new DeleteConfirmPage(player, "我的物品", () -> {
    // 确认后执行的操作
    database.delete(itemId);
}).open();
```

---

## 图标与交互

### 创建图标

```java
// 基本图标
ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
Icon icon = new Icon(item);

// 带点击事件
Icon clickableIcon = new Icon(item).onClick(event -> {
    event.setCancelled(true); // 阻止物品被拿走
    Player player = (Player) event.getWhoClicked();
    player.sendMessage("你点击了这个物品！");
});

// 带 Lore 和名称
ItemStack namedItem = new ItemStack(Material.DIAMOND);
ItemMeta meta = namedItem.getItemMeta();
meta.setDisplayName(ChatColor.AQUA + "神秘钻石");
meta.setLore(Arrays.asList(
    ChatColor.GRAY + "一颗神秘的钻石",
    ChatColor.YELLOW + "点击使用"
));
namedItem.setItemMeta(meta);
Icon namedIcon = new Icon(namedItem);
```

### 放置图标

```java
@Override
protected void setupContent(InventoryOpenEvent event) {
    // 放置到指定槽位
    addItem(0, icon);  // 左上角
    addItem(8, icon);  // 右上角
    addItem(4, icon);  // 顶部中间
    
    // 填充一行
    for (int i = 0; i < 9; i++) {
        addItem(i, borderIcon);
    }
    
    // 填充边框
    fillBorder(borderIcon);
}
```

### 槽位计算

```
槽位编号:
┌─────────────────────────┐
│ 0  1  2  3  4  5  6  7  8 │  第 1 行
│ 9  10 11 12 13 14 15 16 17│  第 2 行
│ 18 19 20 21 22 23 24 25 26│  第 3 行
│ 27 28 29 30 31 32 33 34 35│  第 4 行
│ 36 37 38 39 40 41 42 43 44│  第 5 行
│ 45 46 47 48 49 50 51 52 53│  第 6 行
└─────────────────────────┘

计算公式:
槽位 = 行 * 9 + 列
行 = 槽位 / 9
列 = 槽位 % 9
```

### 动态更新

```java
public class DynamicPage extends BaseInventoryPage {
    
    private int counter = 0;
    
    public DynamicPage(Player player) {
        super(player, "dynamic", "动态页面", 3);
    }
    
    @Override
    protected void setupContent(InventoryOpenEvent event) {
        updateCounter();
        
        // 启动定时更新
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (player.getOpenInventory().getTitle().equals("动态页面")) {
                counter++;
                updateCounter();
            }
        }, 20L, 20L); // 每秒更新
    }
    
    private void updateCounter() {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "计数: " + counter);
        item.setItemMeta(meta);
        
        // 更新指定槽位
        inventory.setItem(13, item);
    }
}
```

---

## 完整示例

### 商店系统

```java
@Getter
public class ShopPage extends BasePaginationPage<ShopItem> {
    
    private final ShopService shopService;
    private final EconomyService economyService;
    
    public ShopPage(Player player, ShopService shopService, EconomyService economyService) {
        super(player, "shop", ChatColor.GOLD + "商店", 6);
        this.shopService = shopService;
        this.economyService = economyService;
    }
    
    @Override
    protected List<ShopItem> getDataList() {
        return shopService.getAllItems();
    }
    
    @Override
    protected Icon createItemIcon(ShopItem shopItem, int index) {
        ItemStack display = shopItem.getDisplayItem().clone();
        ItemMeta meta = display.getItemMeta();
        
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add("");
        lore.add(ChatColor.YELLOW + "价格: " + ChatColor.WHITE + shopItem.getPrice() + " 金币");
        lore.add(ChatColor.GRAY + "库存: " + shopItem.getStock());
        lore.add("");
        lore.add(ChatColor.GREEN + "左键点击购买");
        lore.add(ChatColor.RED + "右键点击查看详情");
        meta.setLore(lore);
        display.setItemMeta(meta);
        
        return new Icon(display).onClick(e -> {
            e.setCancelled(true);
            
            if (e.isLeftClick()) {
                // 购买逻辑
                purchaseItem(shopItem);
            } else if (e.isRightClick()) {
                // 查看详情
                new ItemDetailPage(player, shopItem).open();
            }
        });
    }
    
    private void purchaseItem(ShopItem item) {
        double balance = economyService.getBalance(player);
        
        if (balance < item.getPrice()) {
            player.sendMessage(ChatColor.RED + "金币不足！");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1, 1);
            return;
        }
        
        if (item.getStock() <= 0) {
            player.sendMessage(ChatColor.RED + "库存不足！");
            return;
        }
        
        // 打开确认对话框
        new PurchaseConfirmPage(player, item, () -> {
            economyService.withdraw(player, item.getPrice());
            shopService.decreaseStock(item, 1);
            player.getInventory().addItem(item.getActualItem());
            player.sendMessage(ChatColor.GREEN + "购买成功！");
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1);
            
            // 刷新页面
            new ShopPage(player, shopService, economyService).open();
        }).open();
    }
    
    @Override
    protected void setupContent(InventoryOpenEvent event) {
        super.setupContent(event);
        
        // 添加玩家余额显示
        addItem(4, createBalanceIcon());
        
        // 添加分类按钮
        addItem(45, createCategoryIcon("武器", Material.DIAMOND_SWORD));
        addItem(46, createCategoryIcon("防具", Material.DIAMOND_CHESTPLATE));
        addItem(47, createCategoryIcon("工具", Material.DIAMOND_PICKAXE));
    }
    
    private Icon createBalanceIcon() {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "我的余额");
        meta.setLore(Arrays.asList(
            ChatColor.WHITE + "" + economyService.getBalance(player) + " 金币"
        ));
        item.setItemMeta(meta);
        return new Icon(item);
    }
    
    private Icon createCategoryIcon(String name, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + name);
        item.setItemMeta(meta);
        
        return new Icon(item).onClick(e -> {
            e.setCancelled(true);
            // 切换分类逻辑
        });
    }
}

// 购买确认页面
public class PurchaseConfirmPage extends BaseConfirmationPage {
    
    private final ShopItem item;
    private final Runnable onConfirm;
    
    public PurchaseConfirmPage(Player player, ShopItem item, Runnable onConfirm) {
        super(player, "purchase-confirm", "确认购买");
        this.item = item;
        this.onConfirm = onConfirm;
    }
    
    @Override
    protected Icon getConfirmIcon() {
        ItemStack confirm = new ItemStack(Material.GREEN_WOOL);
        ItemMeta meta = confirm.getItemMeta();
        meta.setDisplayName(ChatColor.GREEN + "确认购买");
        confirm.setItemMeta(meta);
        
        return new Icon(confirm).onClick(e -> {
            e.setCancelled(true);
            player.closeInventory();
            onConfirm.run();
        });
    }
    
    @Override
    protected Icon getCancelIcon() {
        ItemStack cancel = new ItemStack(Material.RED_WOOL);
        ItemMeta meta = cancel.getItemMeta();
        meta.setDisplayName(ChatColor.RED + "取消");
        cancel.setItemMeta(meta);
        
        return new Icon(cancel).onClick(e -> {
            e.setCancelled(true);
            player.closeInventory();
        });
    }
    
    @Override
    protected Icon getInfoIcon() {
        ItemStack info = item.getDisplayItem().clone();
        ItemMeta meta = info.getItemMeta();
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(ChatColor.YELLOW + "价格: " + ChatColor.WHITE + item.getPrice() + " 金币");
        lore.add("");
        lore.add(ChatColor.GRAY + "确认购买此物品？");
        meta.setLore(lore);
        info.setItemMeta(meta);
        return new Icon(info);
    }
}
```

---

## 最佳实践

### 推荐做法

1. **始终取消点击事件**
   ```java
   icon.onClick(e -> {
       e.setCancelled(true); // 防止物品被拿走
       // 处理逻辑
   });
   ```

2. **使用有意义的页面 ID**
   ```java
   super(player, "my-plugin-shop", "商店", 6);
   ```

3. **提供视觉反馈**
   ```java
   player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1, 1);
   ```

4. **处理边界情况**
   ```java
   if (dataList.isEmpty()) {
       addItem(22, createEmptyIcon("暂无数据"));
   }
   ```

### 避免做法

1. **避免在 GUI 中执行耗时操作**
2. **避免创建过于复杂的嵌套页面**
3. **避免忽略关闭事件中的清理**

---

> **下一步**: 阅读 [WebSocket 集成](./WEBSOCKET.md) 了解 UltiPanel 集成
