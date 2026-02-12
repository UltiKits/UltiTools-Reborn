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
