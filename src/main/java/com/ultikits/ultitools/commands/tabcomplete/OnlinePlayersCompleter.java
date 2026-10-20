package com.ultikits.ultitools.commands.tabcomplete;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Completer that suggests online player names.
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public class OnlinePlayersCompleter implements TabCompleter {
    
    private final boolean excludeSelf;
    private final boolean vanishedVisible;
    
    /**
     * Creates a completer that includes all online players.
     */
    public OnlinePlayersCompleter() {
        this(false, false);
    }
    
    /**
     * Creates a completer with custom options.
     *
     * @param excludeSelf     whether to exclude the requesting player
     * @param vanishedVisible whether to show vanished players
     */
    public OnlinePlayersCompleter(boolean excludeSelf, boolean vanishedVisible) {
        this.excludeSelf = excludeSelf;
        this.vanishedVisible = vanishedVisible;
    }
    
    @Override
    public List<String> complete(TabCompletionContext context) {
        Player requester = context.getPlayer();
        String input = context.getCurrentInput().toLowerCase();
        
        List<String> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Skip self if configured
            if (excludeSelf && player.equals(requester)) {
                continue;
            }
            
            // Skip vanished players unless configured to show
            if (!vanishedVisible && !requester.canSee(player)) {
                continue;
            }
            
            String name = player.getName();
            if (name.toLowerCase().startsWith(input)) {
                players.add(name);
            }
        }
        
        Collections.sort(players);
        return players;
    }
}
