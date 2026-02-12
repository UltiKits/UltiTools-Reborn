package com.ultikits.plugins.menu.services;

import com.ultikits.plugins.menu.model.MenuDefinition;
import com.ultikits.ultitools.interfaces.BaseService;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;

/**
 * Service for managing custom menus.
 * 自定义菜单管理服务
 */
public interface MenuService extends BaseService {
    /**
     * Load all menu definitions from configuration files.
     * 从配置文件加载所有菜单定义
     */
    void loadMenus();

    /**
     * Reload all menu configurations.
     * 重新加载所有菜单配置
     */
    void reload();

    /**
     * Get a menu definition by name.
     * 根据名称获取菜单定义
     *
     * @param name menu name (case-insensitive)
     * @return menu definition or null if not found
     */
    @Nullable
    MenuDefinition getMenu(String name);

    /**
     * Get all loaded menu definitions.
     * 获取所有已加载的菜单定义
     *
     * @return collection of all menus
     */
    Collection<MenuDefinition> getAllMenus();

    /**
     * Get all menu names.
     * 获取所有菜单名称
     *
     * @return list of menu names
     */
    List<String> getMenuNames();
}
