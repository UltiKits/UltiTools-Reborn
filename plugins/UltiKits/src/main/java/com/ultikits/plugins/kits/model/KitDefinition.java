package com.ultikits.plugins.kits.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class KitDefinition {
    private String name;
    private String displayName = "&7Kit";
    private List<String> description = new ArrayList<>();
    private String icon = "CHEST";
    private double price = 0;
    private int levelRequired = 0;
    private String permission = "";
    private boolean reBuyable = false;
    private long cooldown = 0;
    private List<String> playerCommands = new ArrayList<>();
    private List<String> consoleCommands = new ArrayList<>();
    private String items = "";

    public boolean isFree() {
        return price <= 0;
    }

    public boolean isOneTime() {
        return !reBuyable;
    }

    public boolean hasPermission() {
        return permission != null && !permission.isEmpty();
    }

    public boolean hasLevelRequirement() {
        return levelRequired > 0;
    }

    public boolean hasItems() {
        return items != null && !items.isEmpty();
    }
}
