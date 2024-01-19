package com.ultikits.plugins.basic.listeners;

import com.ultikits.plugins.basic.tasks.DeathPunishmentTask;
import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

/**
 * @author qianmo, wisdomme
 */
@EventListener(manualRegister = true)
public class DeathListener implements Listener {
    private static final List<Player> list = new ArrayList<>();
    @Autowired
    private DeathPunishmentTask deathPunishmentTask;

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (list.contains(player)) {
            list.remove(player);
            deathPunishmentTask.addPlayerToQueue(player);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();

        if (player.getHealth() > event.getFinalDamage()) {
            return;
        }
        list.add(player);
    }
}
