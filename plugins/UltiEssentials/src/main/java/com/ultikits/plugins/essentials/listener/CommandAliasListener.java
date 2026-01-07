package com.ultikits.plugins.essentials.listener;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Map;

/**
 * Listener for command alias processing.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@EventListener
public class CommandAliasListener implements Listener {
    
    @Autowired
    private EssentialsConfig config;
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!config.isCommandAliasEnabled()) {
            return;
        }
        
        String message = event.getMessage();
        if (!message.startsWith("/")) {
            return;
        }
        
        // Extract command (without /)
        String[] parts = message.substring(1).split(" ", 2);
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";
        
        // Check aliases
        Map<String, String> aliases = config.getCommandAliases();
        
        if (aliases.containsKey(command)) {
            String target = aliases.get(command);
            String newMessage = "/" + target + (args.isEmpty() ? "" : " " + args);
            event.setMessage(newMessage);
        }
    }
}
