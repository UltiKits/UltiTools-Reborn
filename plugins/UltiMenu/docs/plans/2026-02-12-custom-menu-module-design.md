# Custom Menu Module (UltiMenu) Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a YAML-configured custom GUI menu module for UltiTools-API v6.2.0 that lets server admins create interactive menus with buttons, commands, economy costs, sub-menu navigation, item binding, and PlaceholderAPI support.

**Architecture:** Each menu is a separate YAML file in a `menus/` folder. A `MenuService` loads and caches all menus at startup. A `MenuGui` extends `BaseInventoryPage` (not paginated — fixed slot layout) and renders buttons dynamically from config. An `ItemBindListener` detects right-click on bound items. PlaceholderAPI integration is optional via runtime class check. Economy uses `EconomyUtils` (Vault). Global 200ms click debounce prevents double-clicks.

**Tech Stack:** Java 8, UltiTools-API 6.2.0, obliviate-invs (Gui/Icon), Vault via EconomyUtils, PlaceholderAPI (optional soft-dep), Bukkit YamlConfiguration for menu files.

**GitHub Issue:** https://github.com/UltiKits/UltiTools-Reborn/issues/84

---

## File Structure

```
Plugins/UltiMenu/
├── pom.xml
└── src/main/
    ├── java/com/ultikits/plugins/menu/
    │   ├── PluginMain.java
    │   ├── commands/
    │   │   └── MenuCommands.java
    │   ├── config/
    │   │   └── MenuConfig.java            # Global module config
    │   ├── gui/
    │   │   └── CustomMenuGui.java          # Renders a menu from MenuDefinition
    │   ├── listener/
    │   │   └── ItemBindListener.java       # Right-click bound item → open menu
    │   ├── model/
    │   │   ├── MenuDefinition.java         # POJO: parsed menu YAML
    │   │   └── ButtonDefinition.java       # POJO: parsed button config
    │   └── services/
    │       ├── MenuService.java            # Interface
    │       └── MenuServiceImpl.java        # Load, cache, reload menus
    └── resources/
        ├── plugin.yml
        ├── config/config.yml               # Global config (click cooldown, etc.)
        ├── menus/                           # Default example menus
        │   └── example.yml
        └── lang/
            ├── en.json
            └── zh.json
```

---

### Task 1: Project Scaffolding — pom.xml and plugin.yml

**Files:**
- Create: `Plugins/UltiMenu/pom.xml`
- Create: `Plugins/UltiMenu/src/main/resources/plugin.yml`

**Step 1: Create pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <artifactId>UltiMenu</artifactId>
    <groupId>com.ultikits.plugins</groupId>
    <version>1.0.0</version>
    <modelVersion>4.0.0</modelVersion>

    <properties>
        <java.version>1.8</java.version>
        <maven.compiler.target>1.8</maven.compiler.target>
        <maven.compiler.source>1.8</maven.compiler.source>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>com.ultikits</groupId>
            <artifactId>UltiTools-API</artifactId>
            <version>6.2.0</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.spigotmc</groupId>
            <artifactId>spigot-api</artifactId>
            <version>1.19.3-R0.1-SNAPSHOT</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>1.18.24</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>me.clip</groupId>
            <artifactId>placeholderapi</artifactId>
            <version>2.11.6</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>

    <repositories>
        <repository>
            <id>spigotmc-repo</id>
            <url>https://hub.spigotmc.org/nexus/content/repositories/snapshots/</url>
        </repository>
        <repository>
            <id>sonatype</id>
            <url>https://oss.sonatype.org/content/groups/public/</url>
        </repository>
        <repository>
            <id>placeholderapi</id>
            <url>https://repo.extendedclip.com/content/repositories/placeholderapi/</url>
        </repository>
    </repositories>

    <build>
        <resources>
            <resource>
                <directory>src/main/resources</directory>
                <filtering>true</filtering>
            </resource>
        </resources>
    </build>
</project>
```

**Step 2: Create plugin.yml**

```yaml
name: UltiTools-Menu
version: '${project.version}'
main: com.ultikits.plugins.menu.PluginMain
base-package: com.ultikits.plugins.menu
api-version: 620
authors: [ wisdomme ]
softdepend: [ PlaceholderAPI, Vault ]
```

**Step 3: Commit**

```bash
git add Plugins/UltiMenu/pom.xml Plugins/UltiMenu/src/main/resources/plugin.yml
git commit -m "feat(UltiMenu): scaffold project with pom.xml and plugin.yml"
```

---

### Task 2: Data Models — MenuDefinition and ButtonDefinition

**Files:**
- Create: `Plugins/UltiMenu/src/main/java/com/ultikits/plugins/menu/model/ButtonDefinition.java`
- Create: `Plugins/UltiMenu/src/main/java/com/ultikits/plugins/menu/model/MenuDefinition.java`

These are plain POJOs (not `@Table` entities — menus are config-driven, not stored in DB).

**Step 1: Create ButtonDefinition**

```java
package com.ultikits.plugins.menu.model;

import lombok.Data;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

@Data
public class ButtonDefinition {
    private String id;
    private Material item = Material.STONE;
    private int position = 0;
    private String name = "";
    private List<String> lore = new ArrayList<>();
    private List<String> playerCommands = new ArrayList<>();
    private List<String> consoleCommands = new ArrayList<>();
    private double price = 0;
    private String openMenu = null;
    private boolean closeOnClick = true;
    private String permission = null;
    private int customModelData = 0;
}
```

**Step 2: Create MenuDefinition**

```java
package com.ultikits.plugins.menu.model;

import lombok.Data;
import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class MenuDefinition {
    private String fileName;
    private int size = 27;
    private String title = "&7Menu";
    private String command = null;
    private String permission = null;
    private Material bindItem = null;
    private String bindName = null;
    private String bindLore = null;
    private Map<String, ButtonDefinition> buttons = new LinkedHashMap<>();
}
```

**Step 3: Commit**

```bash
git add Plugins/UltiMenu/src/main/java/com/ultikits/plugins/menu/model/
git commit -m "feat(UltiMenu): add MenuDefinition and ButtonDefinition data models"
```

---

### Task 3: MenuService — Load and Cache Menus from YAML

**Files:**
- Create: `Plugins/UltiMenu/src/main/java/com/ultikits/plugins/menu/services/MenuService.java`
- Create: `Plugins/UltiMenu/src/main/java/com/ultikits/plugins/menu/services/MenuServiceImpl.java`

**Key design decisions:**
- Uses `YamlConfiguration.loadConfiguration(file)` to read menu YAML files
- Menus cached in a `Map<String, MenuDefinition>` keyed by filename (without `.yml`)
- `reload()` clears cache and re-reads all files
- Validates size is multiple of 9, material names are valid
- Logs warnings for invalid configs but doesn't crash

**Step 1: Create MenuService interface**

```java
package com.ultikits.plugins.menu.services;

import com.ultikits.plugins.menu.model.MenuDefinition;
import com.ultikits.ultitools.interfaces.BaseService;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;

public interface MenuService extends BaseService {
    void loadMenus();
    void reload();
    @Nullable MenuDefinition getMenu(String name);
    Collection<MenuDefinition> getAllMenus();
    List<String> getMenuNames();
    @Nullable MenuDefinition getMenuByBindItem(String materialName, String lore);
}
```

**Step 2: Create MenuServiceImpl**

The implementation should:
1. In constructor, accept `UltiToolsPlugin` injection
2. On `loadMenus()`:
   - Get the `menus/` folder from `plugin.getResourceFolderPath() + "/menus"`
   - If folder doesn't exist, create it and copy `example.yml` from resources
   - Iterate all `.yml` files in the folder
   - Parse each file into a `MenuDefinition`
   - Store in cache map
3. Parsing logic (private `parseMenu(File)` method):
   - Read `size`, `title`, `command`, `permission`, `bind-item`, `bind-name`, `bind-lore`
   - Validate `size` is multiple of 9 and between 9–54
   - Read `buttons` section — each key is a button ID
   - For each button: read `item` (validate Material), `position`, `name`, `lore`, `player-commands`, `console-commands`, `price`, `open-menu`, `close-on-click`, `permission`, `custom-model-data`
   - Log warning for invalid material names, skip the button
   - Log warning for positions out of range, skip the button
4. `getMenuByBindItem()` iterates all menus looking for matching bind-item material and lore

```java
package com.ultikits.plugins.menu.services;

import com.ultikits.plugins.menu.model.ButtonDefinition;
import com.ultikits.plugins.menu.model.MenuDefinition;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.springframework.stereotype.Service;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;

@Service
public class MenuServiceImpl implements MenuService {
    private final UltiToolsPlugin plugin;
    private final Map<String, MenuDefinition> menuCache = new LinkedHashMap<>();
    private final Logger logger;

    public MenuServiceImpl(UltiToolsPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getPluginLogger();
        loadMenus();
    }

    @Override
    public void loadMenus() {
        menuCache.clear();
        File menusFolder = getMenusFolder();

        File[] files = menusFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            logger.info(plugin.i18n("没有找到菜单配置文件"));
            return;
        }

        for (File file : files) {
            try {
                MenuDefinition menu = parseMenu(file);
                if (menu != null) {
                    String name = file.getName().replace(".yml", "");
                    menu.setFileName(name);
                    menuCache.put(name.toLowerCase(), menu);
                    logger.info(plugin.i18n("已加载菜单: ") + name);
                }
            } catch (Exception e) {
                logger.warning(plugin.i18n("加载菜单失败: ") + file.getName() + " - " + e.getMessage());
            }
        }

        logger.info(String.format(plugin.i18n("共加载 %d 个菜单"), menuCache.size()));
    }

    @Override
    public void reload() {
        loadMenus();
    }

    @Override
    @Nullable
    public MenuDefinition getMenu(String name) {
        return menuCache.get(name.toLowerCase());
    }

    @Override
    public Collection<MenuDefinition> getAllMenus() {
        return Collections.unmodifiableCollection(menuCache.values());
    }

    @Override
    public List<String> getMenuNames() {
        return new ArrayList<>(menuCache.keySet());
    }

    @Override
    @Nullable
    public MenuDefinition getMenuByBindItem(String materialName, String lore) {
        for (MenuDefinition menu : menuCache.values()) {
            if (menu.getBindItem() != null
                    && menu.getBindItem().name().equalsIgnoreCase(materialName)
                    && menu.getBindLore() != null
                    && menu.getBindLore().equals(lore)) {
                return menu;
            }
        }
        return null;
    }

    private File getMenusFolder() {
        File folder = new File(plugin.getResourceFolderPath(), "menus");
        if (!folder.exists()) {
            folder.mkdirs();
            copyDefaultMenu(folder);
        }
        return folder;
    }

    private void copyDefaultMenu(File folder) {
        try (InputStream is = plugin.getClass().getClassLoader().getResourceAsStream("menus/example.yml")) {
            if (is != null) {
                Files.copy(is, new File(folder, "example.yml").toPath());
            }
        } catch (IOException e) {
            logger.warning("Failed to copy example menu: " + e.getMessage());
        }
    }

    @Nullable
    private MenuDefinition parseMenu(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        MenuDefinition menu = new MenuDefinition();

        // Size validation
        int size = yaml.getInt("size", 27);
        if (size < 9 || size > 54 || size % 9 != 0) {
            logger.warning("Invalid menu size " + size + " in " + file.getName() + ", using 27");
            size = 27;
        }
        menu.setSize(size);

        menu.setTitle(yaml.getString("title", "&7Menu"));
        menu.setCommand(yaml.getString("command", null));
        menu.setPermission(yaml.getString("permission", null));

        // Bind item
        String bindItemStr = yaml.getString("bind-item", null);
        if (bindItemStr != null) {
            try {
                menu.setBindItem(Material.valueOf(bindItemStr.toUpperCase()));
            } catch (IllegalArgumentException e) {
                logger.warning("Invalid bind-item material '" + bindItemStr + "' in " + file.getName());
            }
        }
        menu.setBindName(yaml.getString("bind-name", null));
        menu.setBindLore(yaml.getString("bind-lore", null));

        // Buttons
        ConfigurationSection buttonsSection = yaml.getConfigurationSection("buttons");
        if (buttonsSection != null) {
            for (String buttonId : buttonsSection.getKeys(false)) {
                ConfigurationSection btnSection = buttonsSection.getConfigurationSection(buttonId);
                if (btnSection == null) continue;

                ButtonDefinition button = parseButton(btnSection, buttonId, file.getName(), size);
                if (button != null) {
                    menu.getButtons().put(buttonId, button);
                }
            }
        }

        return menu;
    }

    @Nullable
    private ButtonDefinition parseButton(ConfigurationSection section, String id, String fileName, int menuSize) {
        ButtonDefinition button = new ButtonDefinition();
        button.setId(id);

        // Material
        String itemStr = section.getString("item", "STONE");
        try {
            button.setItem(Material.valueOf(itemStr.toUpperCase()));
        } catch (IllegalArgumentException e) {
            logger.warning("Invalid material '" + itemStr + "' for button '" + id + "' in " + fileName + ", skipping");
            return null;
        }

        // Position
        int position = section.getInt("position", 0);
        if (position < 0 || position >= menuSize) {
            logger.warning("Invalid position " + position + " for button '" + id + "' in " + fileName + ", skipping");
            return null;
        }
        button.setPosition(position);

        button.setName(section.getString("name", ""));
        button.setLore(section.getStringList("lore"));
        button.setPlayerCommands(section.getStringList("player-commands"));
        button.setConsoleCommands(section.getStringList("console-commands"));
        button.setPrice(section.getDouble("price", 0));
        button.setOpenMenu(section.getString("open-menu", null));
        button.setCloseOnClick(section.getBoolean("close-on-click", true));
        button.setPermission(section.getString("permission", null));
        button.setCustomModelData(section.getInt("custom-model-data", 0));

        return button;
    }

    @Override public String getName() { return "自定义菜单功能"; }
    @Override public String getResourceFolderName() { return "menu"; }
    @Override public String getAuthor() { return "wisdomme"; }
    @Override public int getVersion() { return 1; }
}
```

**Step 3: Commit**

```bash
git add Plugins/UltiMenu/src/main/java/com/ultikits/plugins/menu/services/
git commit -m "feat(UltiMenu): implement MenuService for loading and caching YAML menus"
```

---

### Task 4: CustomMenuGui — Render Menu with Buttons

**Files:**
- Create: `Plugins/UltiMenu/src/main/java/com/ultikits/plugins/menu/gui/CustomMenuGui.java`

**Key design decisions:**
- Extends `Gui` directly (not `BaseInventoryPage`) — custom menus have fixed slot layouts, not paginated content
- Constructor takes `Player`, `MenuDefinition`, and `MenuService`
- In `onOpen()`, iterates all buttons and places `Icon` at each position
- Click handler: checks permission, checks/deducts price, executes commands, opens sub-menu
- PlaceholderAPI integration via static helper method with runtime class check
- `{player}` replacement in commands
- 200ms debounce: tracks `lastClickTime` per GUI instance

```java
package com.ultikits.plugins.menu.gui;

import com.ultikits.plugins.menu.model.ButtonDefinition;
import com.ultikits.plugins.menu.model.MenuDefinition;
import com.ultikits.plugins.menu.services.MenuService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.utils.EconomyUtils;
import mc.obliviate.inventory.Gui;
import mc.obliviate.inventory.Icon;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class CustomMenuGui extends Gui {
    private final UltiToolsPlugin plugin;
    private final MenuDefinition menu;
    private final MenuService menuService;
    private long lastClickTime = 0;
    private static final long CLICK_DEBOUNCE_MS = 200;

    public CustomMenuGui(Player player, UltiToolsPlugin plugin, MenuDefinition menu, MenuService menuService) {
        super(player, "custom-menu-" + menu.getFileName(),
                ChatColor.translateAlternateColorCodes('&', parsePlaceholders(player, menu.getTitle())),
                menu.getSize() / 9);
        this.plugin = plugin;
        this.menu = menu;
        this.menuService = menuService;
    }

    @Override
    public void onOpen(InventoryOpenEvent event) {
        Player viewer = (Player) event.getPlayer();
        for (ButtonDefinition button : menu.getButtons().values()) {
            Icon icon = createButtonIcon(viewer, button);
            addItem(button.getPosition(), icon);
        }
    }

    private Icon createButtonIcon(Player viewer, ButtonDefinition button) {
        ItemStack itemStack = new ItemStack(button.getItem());
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            // Name with placeholders and color codes
            if (!button.getName().isEmpty()) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                        parsePlaceholders(viewer, button.getName())));
            }

            // Lore with placeholders and color codes
            if (!button.getLore().isEmpty()) {
                List<String> coloredLore = new ArrayList<>();
                for (String line : button.getLore()) {
                    coloredLore.add(ChatColor.translateAlternateColorCodes('&',
                            parsePlaceholders(viewer, line)));
                }
                meta.setLore(coloredLore);
            }

            // Custom model data
            if (button.getCustomModelData() > 0) {
                meta.setCustomModelData(button.getCustomModelData());
            }

            itemStack.setItemMeta(meta);
        }

        Icon icon = new Icon(itemStack);
        icon.onClick(e -> handleButtonClick(viewer, button));
        return icon;
    }

    private void handleButtonClick(Player player, ButtonDefinition button) {
        // Debounce
        long now = System.currentTimeMillis();
        if (now - lastClickTime < CLICK_DEBOUNCE_MS) {
            return;
        }
        lastClickTime = now;

        // Permission check
        if (button.getPermission() != null && !player.hasPermission(button.getPermission())) {
            player.sendMessage(ChatColor.RED + plugin.i18n("你没有权限使用此按钮！"));
            return;
        }

        // Economy check
        if (button.getPrice() > 0) {
            if (!EconomyUtils.isAvailable()) {
                player.sendMessage(ChatColor.RED + plugin.i18n("经济系统不可用！"));
                return;
            }
            if (!EconomyUtils.has(player, button.getPrice())) {
                player.sendMessage(ChatColor.RED + String.format(
                        plugin.i18n("余额不足！需要 %s"),
                        EconomyUtils.format(button.getPrice())));
                return;
            }
            if (!EconomyUtils.withdraw(player, button.getPrice())) {
                player.sendMessage(ChatColor.RED + plugin.i18n("扣款失败！"));
                return;
            }
            player.sendMessage(ChatColor.GREEN + String.format(
                    plugin.i18n("已扣除 %s"),
                    EconomyUtils.format(button.getPrice())));
        }

        // Execute player commands
        for (String cmd : button.getPlayerCommands()) {
            String parsed = cmd.replace("{player}", player.getName());
            parsed = parsePlaceholders(player, parsed);
            player.performCommand(parsed);
        }

        // Execute console commands on main thread
        if (!button.getConsoleCommands().isEmpty()) {
            for (String cmd : button.getConsoleCommands()) {
                String parsed = cmd.replace("{player}", player.getName());
                parsed = parsePlaceholders(player, parsed);
                String finalParsed = parsed;
                Bukkit.getScheduler().runTask(
                        Bukkit.getPluginManager().getPlugin("UltiTools"),
                        () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalParsed));
            }
        }

        // Open sub-menu
        if (button.getOpenMenu() != null) {
            MenuDefinition subMenu = menuService.getMenu(button.getOpenMenu());
            if (subMenu != null) {
                // Close current first, then open new on next tick
                player.closeInventory();
                Bukkit.getScheduler().runTask(
                        Bukkit.getPluginManager().getPlugin("UltiTools"),
                        () -> new CustomMenuGui(player, plugin, subMenu, menuService).open());
                return;
            } else {
                player.sendMessage(ChatColor.RED + String.format(
                        plugin.i18n("菜单 '%s' 不存在！"), button.getOpenMenu()));
            }
        }

        // Close on click
        if (button.isCloseOnClick() && button.getOpenMenu() == null) {
            player.closeInventory();
        }
    }

    static String parsePlaceholders(Player player, String text) {
        if (text == null) return "";
        // Built-in placeholders
        text = text.replace("{player}", player.getName());
        // PlaceholderAPI (runtime check, no hard dependency)
        try {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                text = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
            }
        } catch (NoClassDefFoundError ignored) {
            // PlaceholderAPI not installed — placeholders stay as-is
        }
        return text;
    }
}
```

**Step 2: Commit**

```bash
git add Plugins/UltiMenu/src/main/java/com/ultikits/plugins/menu/gui/
git commit -m "feat(UltiMenu): implement CustomMenuGui with buttons, economy, commands, sub-menus"
```

---

### Task 5: MenuCommands — `/menu` command

**Files:**
- Create: `Plugins/UltiMenu/src/main/java/com/ultikits/plugins/menu/commands/MenuCommands.java`

**Commands:**
- `/menu <name>` — open a menu (player only)
- `/menu list` — list all menus
- `/menu reload` — reload all menus (admin permission)

```java
package com.ultikits.plugins.menu.commands;

import com.ultikits.plugins.menu.gui.CustomMenuGui;
import com.ultikits.plugins.menu.model.MenuDefinition;
import com.ultikits.plugins.menu.services.MenuService;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(permission = "ultikits.menu.use", description = "自定义菜单", alias = {"menu"})
public class MenuCommands extends AbstractCommendExecutor {
    private final UltiToolsPlugin plugin;
    private final MenuService menuService;

    public MenuCommands(UltiToolsPlugin plugin, MenuService menuService) {
        this.plugin = plugin;
        this.menuService = menuService;
    }

    @CmdMapping(format = "open <name>")
    @CmdTarget(CmdTarget.CmdTargetType.PLAYER)
    public void openMenu(@CmdSender Player player,
                         @CmdParam(value = "name", suggest = "getMenuNames") String name) {
        openMenuForPlayer(player, name);
    }

    @CmdMapping(format = "<name>")
    @CmdTarget(CmdTarget.CmdTargetType.PLAYER)
    public void openMenuShort(@CmdSender Player player,
                              @CmdParam(value = "name", suggest = "getMenuNames") String name) {
        // Prevent clashing with "list" and "reload" sub-commands
        if ("list".equalsIgnoreCase(name) || "reload".equalsIgnoreCase(name)) {
            return;
        }
        openMenuForPlayer(player, name);
    }

    @CmdMapping(format = "list")
    public void listMenus(@CmdSender CommandSender sender) {
        List<String> names = menuService.getMenuNames();
        if (names.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + plugin.i18n("没有可用的菜单"));
            return;
        }
        sender.sendMessage(ChatColor.GOLD + plugin.i18n("=== 可用菜单 ==="));
        for (String name : names) {
            MenuDefinition menu = menuService.getMenu(name);
            String title = menu != null ? ChatColor.translateAlternateColorCodes('&', menu.getTitle()) : "";
            sender.sendMessage(ChatColor.YELLOW + "  " + name + ChatColor.GRAY + " - " + title);
        }
    }

    @CmdMapping(format = "reload", permission = "ultikits.menu.admin")
    public void reloadMenus(@CmdSender CommandSender sender) {
        menuService.reload();
        sender.sendMessage(ChatColor.GREEN + String.format(
                plugin.i18n("已重新加载 %d 个菜单"),
                menuService.getAllMenus().size()));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(plugin.i18n(
                "=== 自定义菜单 ===\n" +
                "/menu <名字> 打开菜单\n" +
                "/menu list 列出所有菜单\n" +
                "/menu reload 重新加载菜单\n" +
                "================"));
    }

    private void openMenuForPlayer(Player player, String name) {
        MenuDefinition menu = menuService.getMenu(name);
        if (menu == null) {
            player.sendMessage(ChatColor.RED + String.format(
                    plugin.i18n("菜单 '%s' 不存在！"), name));
            return;
        }
        if (menu.getPermission() != null && !player.hasPermission(menu.getPermission())) {
            player.sendMessage(ChatColor.RED + plugin.i18n("你没有权限打开此菜单！"));
            return;
        }
        new CustomMenuGui(player, plugin, menu, menuService).open();
    }

    private List<String> getMenuNames(Player player) {
        return menuService.getMenuNames();
    }
}
```

**Step 2: Commit**

```bash
git add Plugins/UltiMenu/src/main/java/com/ultikits/plugins/menu/commands/
git commit -m "feat(UltiMenu): implement /menu command with open, list, reload sub-commands"
```

---

### Task 6: ItemBindListener — Right-click to Open Menu

**Files:**
- Create: `Plugins/UltiMenu/src/main/java/com/ultikits/plugins/menu/listener/ItemBindListener.java`

Listens for `PlayerInteractEvent` (right-click), checks if the held item matches any menu's bind configuration, and opens the menu.

```java
package com.ultikits.plugins.menu.listener;

import com.ultikits.plugins.menu.gui.CustomMenuGui;
import com.ultikits.plugins.menu.model.MenuDefinition;
import com.ultikits.plugins.menu.services.MenuService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

@EventListener
public class ItemBindListener implements Listener {
    private final UltiToolsPlugin plugin;
    private final MenuService menuService;

    public ItemBindListener(UltiToolsPlugin plugin, MenuService menuService) {
        this.plugin = plugin;
        this.menuService = menuService;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        // Check main hand first, then off-hand
        MenuDefinition menu = findMatchingMenu(item);
        if (menu == null) {
            item = player.getInventory().getItemInOffHand();
            menu = findMatchingMenu(item);
        }

        if (menu == null) {
            return;
        }

        event.setCancelled(true);

        if (menu.getPermission() != null && !player.hasPermission(menu.getPermission())) {
            player.sendMessage(ChatColor.RED + plugin.i18n("你没有权限打开此菜单！"));
            return;
        }

        new CustomMenuGui(player, plugin, menu, menuService).open();
    }

    private MenuDefinition findMatchingMenu(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }

        for (MenuDefinition menu : menuService.getAllMenus()) {
            if (menu.getBindItem() == null) {
                continue;
            }
            if (menu.getBindItem() != item.getType()) {
                continue;
            }

            // Check bind-name if specified
            if (menu.getBindName() != null) {
                ItemMeta meta = item.getItemMeta();
                if (meta == null || !meta.hasDisplayName()) continue;
                String displayName = ChatColor.stripColor(meta.getDisplayName());
                String expected = ChatColor.stripColor(
                        ChatColor.translateAlternateColorCodes('&', menu.getBindName()));
                if (!displayName.equals(expected)) continue;
            }

            // Check bind-lore if specified
            if (menu.getBindLore() != null) {
                ItemMeta meta = item.getItemMeta();
                if (meta == null || !meta.hasLore()) continue;
                List<String> lore = meta.getLore();
                String expected = ChatColor.stripColor(
                        ChatColor.translateAlternateColorCodes('&', menu.getBindLore()));
                boolean found = false;
                for (String line : lore) {
                    if (ChatColor.stripColor(line).contains(expected)) {
                        found = true;
                        break;
                    }
                }
                if (!found) continue;
            }

            return menu;
        }
        return null;
    }
}
```

**Step 2: Commit**

```bash
git add Plugins/UltiMenu/src/main/java/com/ultikits/plugins/menu/listener/
git commit -m "feat(UltiMenu): add ItemBindListener for right-click menu opening"
```

---

### Task 7: Config, i18n, Example Menu, and PluginMain

**Files:**
- Create: `Plugins/UltiMenu/src/main/java/com/ultikits/plugins/menu/config/MenuConfig.java`
- Create: `Plugins/UltiMenu/src/main/java/com/ultikits/plugins/menu/PluginMain.java`
- Create: `Plugins/UltiMenu/src/main/resources/config/config.yml`
- Create: `Plugins/UltiMenu/src/main/resources/menus/example.yml`
- Create: `Plugins/UltiMenu/src/main/resources/lang/en.json`
- Create: `Plugins/UltiMenu/src/main/resources/lang/zh.json`

**Step 1: Create MenuConfig**

```java
package com.ultikits.plugins.menu.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigEntity("config/config.yml")
public class MenuConfig extends AbstractConfigEntity {
    @ConfigEntry(path = "click_cooldown_ms", comment = "Global click debounce in milliseconds")
    @Range(min = 50, max = 5000)
    private int clickCooldownMs = 200;

    public MenuConfig(String configFilePath) {
        super(configFilePath);
    }
}
```

**Step 2: Create PluginMain**

```java
package com.ultikits.plugins.menu;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

import java.util.Arrays;
import java.util.List;

@UltiToolsModule
public class PluginMain extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        return true;
    }

    @Override
    public void unregisterSelf() {
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }
}
```

**Step 3: Create config/config.yml**

```yaml
# Global click debounce in milliseconds
click_cooldown_ms: 200
```

**Step 4: Create menus/example.yml**

```yaml
# Example menu — copy and customize!
# 示例菜单 — 复制并自定义！
size: 27
title: "&6&l Server Menu &r&7- {player}"
command: servermenu
permission: null
bind-item: COMPASS
bind-name: "&6Server Menu"
bind-lore: "&eRight-click to open"

buttons:
  info:
    item: BOOK
    position: 10
    name: "&b&lServer Info"
    lore:
      - "&7Welcome to the server!"
      - "&7Online: &a%server_online%"
    player-commands: []
    console-commands: []
    price: 0
    close-on-click: false

  spawn:
    item: ENDER_PEARL
    position: 12
    name: "&d&lSpawn"
    lore:
      - "&7Teleport to spawn"
      - "&7Cost: &aFree"
    player-commands:
      - "spawn"
    console-commands: []
    price: 0

  kit-starter:
    item: IRON_SWORD
    position: 14
    name: "&e&lStarter Kit"
    lore:
      - "&7Get starter items"
      - "&7Cost: &c$100"
    player-commands: []
    console-commands:
      - "give {player} iron_sword 1"
      - "give {player} iron_pickaxe 1"
      - "give {player} bread 16"
    price: 100

  rules:
    item: WRITABLE_BOOK
    position: 16
    name: "&c&lServer Rules"
    lore:
      - "&7Click to view rules"
    open-menu: rules
    price: 0
```

**Step 5: Create lang/en.json**

```json
{
  "自定义菜单功能": "Custom Menu",
  "没有找到菜单配置文件": "No menu configuration files found",
  "已加载菜单: ": "Menu loaded: ",
  "加载菜单失败: ": "Failed to load menu: ",
  "共加载 %d 个菜单": "Loaded %d menus",
  "菜单 '%s' 不存在！": "Menu '%s' does not exist!",
  "你没有权限打开此菜单！": "You don't have permission to open this menu!",
  "你没有权限使用此按钮！": "You don't have permission to use this button!",
  "经济系统不可用！": "Economy system is not available!",
  "余额不足！需要 %s": "Insufficient balance! Requires %s",
  "扣款失败！": "Withdrawal failed!",
  "已扣除 %s": "Deducted %s",
  "没有可用的菜单": "No menus available",
  "=== 可用菜单 ===": "=== Available Menus ===",
  "已重新加载 %d 个菜单": "Reloaded %d menus",
  "=== 自定义菜单 ===\n/menu <名字> 打开菜单\n/menu list 列出所有菜单\n/menu reload 重新加载菜单\n================": "=== Custom Menu ===\n/menu <name> Open a menu\n/menu list List all menus\n/menu reload Reload all menus\n================"
}
```

**Step 6: Create lang/zh.json**

```json
{
}
```

**Step 7: Commit**

```bash
git add Plugins/UltiMenu/
git commit -m "feat(UltiMenu): add PluginMain, config, i18n, and example menu"
```

---

### Task 8: Wire Config Cooldown into CustomMenuGui

**Files:**
- Modify: `Plugins/UltiMenu/src/main/java/com/ultikits/plugins/menu/gui/CustomMenuGui.java`

Update the debounce value to read from `MenuConfig` instead of the hardcoded 200ms constant.

**Step 1: Update CustomMenuGui constructor to accept MenuConfig**

Add `MenuConfig` parameter. Read `clickCooldownMs` from it. The GUI gets `MenuConfig` from the plugin context:

```java
// In constructor, add:
private final long clickDebounceMs;

// In constructor body, get config:
MenuConfig config = plugin.getConfig(MenuConfig.class);
this.clickDebounceMs = config != null ? config.getClickCooldownMs() : 200;

// In handleButtonClick, replace CLICK_DEBOUNCE_MS with clickDebounceMs
```

**Step 2: Commit**

```bash
git add Plugins/UltiMenu/src/main/java/com/ultikits/plugins/menu/gui/CustomMenuGui.java
git commit -m "feat(UltiMenu): use configurable click cooldown from MenuConfig"
```

---

### Task 9: Build and Manual Verification

**Step 1: Build the module**

```bash
cd Plugins/UltiMenu && mvn clean package -DskipTests
```

Expected: BUILD SUCCESS, JAR at `target/UltiMenu-1.0.0.jar`

**Step 2: Verify no compilation errors**

If build fails, fix imports and Java 8 compatibility issues.

**Step 3: Final commit if any fixes needed**

---

## Improvements Beyond Base Requirements

These enhancements are included in the implementation above:

1. **`bind-name`** — Optional item display name matching (not just lore), more precise binding
2. **`close-on-click`** — Per-button option to keep menu open after click (useful for info buttons)
3. **`permission` per button** — Fine-grained permission control at button level, not just menu level
4. **`custom-model-data`** — Support resource pack custom item textures
5. **`{player}` in commands** — Automatic player name substitution in both player and console commands
6. **Configurable cooldown** — Admin-adjustable via `config.yml`, validated with `@Range`
7. **Tab completion** — `/menu` suggests available menu names
8. **Example menu with real patterns** — Ships with a useful server menu template, not just hello-world
