package com.ultikits.services;


import com.ultikits.entity.HomeEntity;
import com.ultikits.PluginMain;
import com.ultikits.entity.WorldLocation;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.services.TeleportService;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class HomeServiceImpl implements HomeService {
    DataOperator<HomeEntity> dataOperator = PluginMain.getPluginMain().getDataOperator(HomeEntity.class);

    @Override
    public HomeEntity getHomeByName(UUID playerId, String name) {
        Collection<HomeEntity> homeEntities = dataOperator.getAll(WhereCondition.builder().column("playerId").value(playerId).build(),
                WhereCondition.builder().column("name").value(name).build());
        if (homeEntities.size() == 0) {
            return null;
        }
        return new ArrayList<>(homeEntities).get(0);
    }

    @Override
    public List<HomeEntity> getHomeList(UUID playerId) {
        Collection<HomeEntity> all = dataOperator.getAll(WhereCondition.builder().column("playerId").value(playerId).build());
        return new ArrayList<>(all);
    }

    @Override
    public boolean createHome(Player player, String name) {
        boolean exist = dataOperator.exist(WhereCondition.builder().column("playerId").value(player.getUniqueId()).build(),
                WhereCondition.builder().column("name").value(name).build());
        if (exist) {
            return false;
        }
        HomeEntity homeEntity = new HomeEntity();
        homeEntity.setPlayerId(player.getUniqueId());
        homeEntity.setLocation(new WorldLocation(player.getLocation()));
        homeEntity.setName(name);
        dataOperator.insert(homeEntity);
        return true;
    }

    @Override
    public void deleteHome(UUID playerId, String name) {
        dataOperator.del(WhereCondition.builder().column("playerId").value(playerId).build(),
                WhereCondition.builder().column("name").value(name).build());
    }

    @Override
    public void goHome(Player player, String name) {
        HomeEntity homeByName = getHomeByName(player.getUniqueId(), name);
        if (homeByName == null) {
            player.sendMessage(ChatColor.RED + PluginMain.getPluginMain().i18n("你没有这个家！"));
            return;
        }
        Location location = homeByName.getHomeLocation();
        Optional<TeleportService> teleportService = PluginManager.getService(TeleportService.class);
        if (teleportService.isPresent()) {
            teleportService.get().delayTeleport(player, location, 5);
        }else {
            player.teleport(location);
        }
    }

    @Override
    public String getName() {
        return "家功能";
    }

    @Override
    public String getResourceFolderName() {
        return "home";
    }

    @Override
    public String getAuthor() {
        return "wisdomme";
    }

    @Override
    public int getVersion() {
        return 1;
    }
}
