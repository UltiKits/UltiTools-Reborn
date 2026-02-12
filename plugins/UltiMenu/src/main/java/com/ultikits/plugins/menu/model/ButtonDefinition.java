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
