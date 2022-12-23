package com.ultikits.services;


import com.ultikits.PluginMain;
import com.ultikits.entity.HomeEntity;
import com.ultikits.entity.WorldLocation;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.services.TeleportService;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

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
        if (!isPlayerCanSetHome(player)) {
            player.sendMessage(ChatColor.RED + PluginMain.getPluginMain().i18n("你没法创建更多的家！"));
            return false;
        }
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
            YamlConfiguration config = PluginMain.getPluginMain().getConfig("res/config/config.yml");
            int delayTime = config.getInt("home_tpwait");
            teleportService.get().delayTeleport(player, location, delayTime);
        } else {
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

    private boolean isPlayerCanSetHome(Player player) {
        if (player.hasPermission("ultikits.tools.admin")) return true;
        YamlConfiguration homeConfig = PluginMain.getPluginMain().getConfig("res/config/config.yml");
        if (player.hasPermission("ultikits.tools.level1")) {
            if (homeConfig.getInt("home_pro") == 0) return true;
            return getHomeList(player.getUniqueId()).size() < homeConfig.getInt("home_pro");
        } else if (player.hasPermission("ultikits.tools.level2")) {
            if (homeConfig.getInt("home_ultimate") == 0) return true;
            return getHomeList(player.getUniqueId()).size() < homeConfig.getInt("home_ultimate");
        } else {
            if (homeConfig.getInt("home_normal") == 0) return true;
            return getHomeList(player.getUniqueId()).size() < homeConfig.getInt("home_normal");
        }
    }
}
