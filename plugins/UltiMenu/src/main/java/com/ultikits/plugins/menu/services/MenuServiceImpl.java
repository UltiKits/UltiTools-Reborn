package com.ultikits.plugins.menu.services;

import com.ultikits.plugins.menu.model.ButtonDefinition;
import com.ultikits.plugins.menu.model.MenuDefinition;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import com.ultikits.ultitools.annotations.Service;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import java.util.stream.Collectors;

/**
 * Implementation of MenuService.
 * MenuService的实现
 */
@Service
public class MenuServiceImpl implements MenuService {
    private final UltiToolsPlugin plugin;
    private final PluginLogger logger;
    private final Map<String, MenuDefinition> menus = new HashMap<>();

    public MenuServiceImpl(UltiToolsPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        loadMenus();
    }

    @Override
    public void loadMenus() {
        menus.clear();

        File menusFolder = new File(plugin.getResourceFolderPath(), "menus");
        if (!menusFolder.exists()) {
            menusFolder.mkdirs();
            copyExampleMenu(menusFolder);
        }

        File[] files = menusFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            logger.warn(plugin.i18n("没有找到菜单配置文件"));
            return;
        }

        int loadedCount = 0;
        for (File file : files) {
            try {
                MenuDefinition menu = parseMenuFile(file);
                if (menu != null) {
                    String menuName = file.getName().replace(".yml", "").toLowerCase();
                    menu.setFileName(menuName);
                    menus.put(menuName, menu);
                    logger.info(plugin.i18n("已加载菜单: ") + menuName);
                    loadedCount++;
                }
            } catch (Exception e) {
                logger.warn(plugin.i18n("加载菜单失败: ") + file.getName() + " - " + e.getMessage());
            }
        }

        logger.info(String.format(plugin.i18n("共加载 %d 个菜单"), loadedCount));
    }

    @Override
    public void reload() {
        loadMenus();
    }

    @Nullable
    @Override
    public MenuDefinition getMenu(String name) {
        return menus.get(name.toLowerCase());
    }

    @Override
    public Collection<MenuDefinition> getAllMenus() {
        return Collections.unmodifiableCollection(menus.values());
    }

    @Override
    public List<String> getMenuNames() {
        return new ArrayList<>(menus.keySet());
    }

    @Override
    public String getName() {
        return "自定义菜单功能";
    }

    @Override
    public String getResourceFolderName() {
        return "menu";
    }

    @Override
    public String getAuthor() {
        return "wisdomme";
    }

    @Override
    public int getVersion() {
        return 1;
    }

    /**
     * Copy example menu from resources to menus folder.
     * 从资源文件复制示例菜单到菜单文件夹
     */
    private void copyExampleMenu(File folder) {
        try (InputStream is = plugin.getClass().getClassLoader().getResourceAsStream("menus/example.yml")) {
            File exampleFile = new File(folder, "example.yml");
            if (is != null && !exampleFile.exists()) {
                Files.copy(is, exampleFile.toPath());
            }
        } catch (IOException e) {
            logger.warn("Failed to copy example menu: " + e.getMessage());
        }
    }

    /**
     * Parse a menu definition from YAML file.
     * 从YAML文件解析菜单定义
     *
     * @param file the YAML file
     * @return parsed MenuDefinition or null if invalid
     */
    @Nullable
    private MenuDefinition parseMenuFile(File file) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        // Validate size
        int size = config.getInt("size", 27);
        if (size < 9 || size > 54 || size % 9 != 0) {
            logger.warn("Invalid menu size " + size + " in " + file.getName() + " (must be 9-54 and multiple of 9)");
            return null;
        }

        MenuDefinition menu = new MenuDefinition();
        menu.setSize(size);
        menu.setTitle(config.getString("title", "Menu"));
        menu.setCommand(config.getString("command"));
        menu.setPermission(config.getString("permission"));

        // Parse bind-item
        String bindItemStr = config.getString("bind-item");
        if (bindItemStr != null) {
            Material bindMaterial = parseMaterial(bindItemStr);
            if (bindMaterial != null) {
                menu.setBindItem(bindMaterial);
            }
        }

        menu.setBindName(config.getString("bind-name"));
        menu.setBindLore(config.getString("bind-lore"));

        // Parse buttons
        ConfigurationSection buttonsSection = config.getConfigurationSection("buttons");
        if (buttonsSection != null) {
            Map<String, ButtonDefinition> buttons = new HashMap<>();
            for (String buttonId : buttonsSection.getKeys(false)) {
                ButtonDefinition button = parseButton(buttonsSection.getConfigurationSection(buttonId), buttonId, file.getName());
                if (button != null) {
                    buttons.put(buttonId, button);
                }
            }
            menu.setButtons(buttons);
        } else {
            menu.setButtons(new HashMap<>());
        }

        return menu;
    }

    /**
     * Parse a button definition from configuration section.
     * 从配置节解析按钮定义
     *
     * @param section the configuration section
     * @param buttonId the button ID
     * @param fileName the source file name (for logging)
     * @return parsed ButtonDefinition or null if invalid
     */
    @Nullable
    private ButtonDefinition parseButton(ConfigurationSection section, String buttonId, String fileName) {
        if (section == null) {
            return null;
        }

        // Parse item material (required)
        String itemStr = section.getString("item");
        if (itemStr == null) {
            logger.warn("Button " + buttonId + " in " + fileName + " has no item specified, skipping");
            return null;
        }

        Material material = parseMaterial(itemStr);
        if (material == null) {
            logger.warn("Button " + buttonId + " in " + fileName + " has invalid material: " + itemStr + ", skipping");
            return null;
        }

        ButtonDefinition button = new ButtonDefinition();
        button.setId(buttonId);
        button.setItem(material);
        button.setPosition(section.getInt("position", 0));
        button.setName(section.getString("name"));
        button.setLore(section.getStringList("lore"));
        button.setPlayerCommands(section.getStringList("player-commands"));
        button.setConsoleCommands(section.getStringList("console-commands"));
        button.setPrice(section.getDouble("price", 0.0));
        button.setOpenMenu(section.getString("open-menu"));
        button.setCloseOnClick(section.getBoolean("close-on-click", true));
        button.setPermission(section.getString("permission"));
        button.setCustomModelData(section.getInt("custom-model-data", 0));

        return button;
    }

    /**
     * Parse a Material from string, handling legacy names if needed.
     * 从字符串解析Material，必要时处理旧版名称
     *
     * @param materialName the material name
     * @return the Material or null if invalid
     */
    @Nullable
    private Material parseMaterial(String materialName) {
        try {
            return Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
