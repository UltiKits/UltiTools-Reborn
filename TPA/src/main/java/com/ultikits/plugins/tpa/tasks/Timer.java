package com.ultikits.plugins.tpa.tasks;

import com.ultikits.plugins.tpa.services.TpaService;
import com.ultikits.ultitools.manager.PluginManager;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Optional;

public class Timer extends BukkitRunnable {
    private final TpaService tpaService;
    private final Player player;
    private int time;

    public Timer(Player player, int time) {
        this.player = player;
        this.time = time;
        Optional<TpaService> service = PluginManager.getService(TpaService.class);
        if (!service.isPresent()){
            throw new RuntimeException("TPA Service Not Found!");
        }
        this.tpaService = service.get();
    }

    @Override
    public void run() {
        if (!tpaService.isPlayerInTemp(player)) {
            this.cancel();
            return;
        } else {
            time --;
        }
        if (time <= 0) {
            tpaService.rejectTpa(player, true);
            this.cancel();
        }
    }
}
