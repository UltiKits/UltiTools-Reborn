package com.ultikits.plugins.basic.suggests;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class CommonSuggest {
    public List<String> suggestPlayer() {
        List<String> tabCommands = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            tabCommands.add(player.getName());
        }
        return tabCommands;
    }
}
