package com.ultikits.ultitools.commands.tabcomplete;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Completer that suggests world names.
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public class WorldsCompleter implements TabCompleter {
    
    private final World.Environment[] environments;
    
    /**
     * Creates a completer that suggests all worlds.
     */
    public WorldsCompleter() {
        this.environments = null;
    }
    
    /**
     * Creates a completer that only suggests worlds of specific environments.
     *
     * @param environments the environment types to include
     */
    public WorldsCompleter(World.Environment... environments) {
        this.environments = environments;
    }
    
    @Override
    public List<String> complete(TabCompletionContext context) {
        String input = context.getCurrentInput().toLowerCase();
        List<String> worlds = new ArrayList<>();
        
        for (World world : Bukkit.getWorlds()) {
            // Filter by environment if specified
            if (environments != null) {
                boolean matches = false;
                for (World.Environment env : environments) {
                    if (world.getEnvironment() == env) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) {
                    continue;
                }
            }
            
            String name = world.getName();
            if (name.toLowerCase().startsWith(input)) {
                worlds.add(name);
            }
        }
        
        Collections.sort(worlds);
        return worlds;
    }
}
