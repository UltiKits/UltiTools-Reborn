package com.ultikits.plugins.home.services;

import com.ultikits.plugins.home.PluginMain;
import com.ultikits.plugins.home.config.HomeConfig;
import com.ultikits.plugins.home.entity.HomeEntity;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.entities.common.WorldLocation;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.services.TeleportService;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class HomeServiceImpl implements HomeService {
    private final HomeConfig homeConfig;
    private final TeleportService teleportService;

    public HomeServiceImpl(HomeConfig homeConfig, TeleportService teleportService) {
        this.homeConfig = homeConfig;
        this.teleportService = teleportService;
    }

    @Override
    public HomeEntity getHomeByName(UUID playerId, String name) {
        DataOperator<HomeEntity> dataOperator = PluginMain.getPluginMain().getDataOperator(HomeEntity.class);
        Collection<HomeEntity> homeEntities = dataOperator.getAll(
                WhereCondition.builder().column("playerId").value(playerId).build(),
                WhereCondition.builder().column("name").value(name).build()
        );
        if (homeEntities.isEmpty()) {
            return null;
        }
        return new ArrayList<>(homeEntities).get(0);
    }

    @Override
    public List<HomeEntity> getHomeList(UUID playerId) {
        DataOperator<HomeEntity> dataOperator = PluginMain.getPluginMain().getDataOperator(HomeEntity.class);
        Collection<HomeEntity> all = dataOperator.getAll(
                WhereCondition.builder().column("playerId").value(playerId).build()
        );
        return new ArrayList<>(all);
    }

    @Override
    public List<String> getHomeNames(UUID playerId) {
        List<HomeEntity> homeList = getHomeList(playerId);
        List<String> names = new ArrayList<>();
        for (HomeEntity entity : homeList) {
            names.add(entity.getName());
        }
        return names;
    }

    @Override
    public boolean createHome(Player player, String name) {
        if (!isPlayerCanSetHome(player)) {
            player.sendMessage(ChatColor.RED + PluginMain.getPluginMain().i18n("你没法创建更多的家！"));
            return false;
        }
        DataOperator<HomeEntity> dataOperator = PluginMain.getPluginMain().getDataOperator(HomeEntity.class);
        boolean exist = dataOperator.exist(
                WhereCondition.builder().column("playerId").value(player.getUniqueId()).build(),
                WhereCondition.builder().column("name").value(name).build()
        );
        if (exist) {
            return false;
        }
        HomeEntity homeEntity = new HomeEntity();
        homeEntity.setId(new Date().getTime());
        homeEntity.setPlayerId(player.getUniqueId());
        homeEntity.setLocation(new WorldLocation(player.getLocation()));
        homeEntity.setName(name);
        dataOperator.insert(homeEntity);
        return true;
    }

    @Override
    public void deleteHome(UUID playerId, String name) {
        DataOperator<HomeEntity> dataOperator = PluginMain.getPluginMain().getDataOperator(HomeEntity.class);
        dataOperator.del(
                WhereCondition.builder().column("playerId").value(playerId).build(),
                WhereCondition.builder().column("name").value(name).build()
        );
    }

    @Override
    public void goHome(Player player, String name) {
        HomeEntity homeByName = getHomeByName(player.getUniqueId(), name);
        if (homeByName == null) {
            player.sendMessage(ChatColor.RED + PluginMain.getPluginMain().i18n("你没有这个家！"));
            return;
        }
        Location location = homeByName.getHomeLocation();
        int delayTime = homeConfig.getHomeTpWait();
        try {
            teleportService.delayTeleport(player, location, delayTime);
        } catch (Exception ignore) {
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
        if (player.hasPermission("ultikits.tools.level1")) {
            if (homeConfig.getHomePro() == 0) return true;
            return getHomeList(player.getUniqueId()).size() < homeConfig.getHomePro();
        } else if (player.hasPermission("ultikits.tools.level2")) {
            if (homeConfig.getHomeUltimate() == 0) return true;
            return getHomeList(player.getUniqueId()).size() < homeConfig.getHomeUltimate();
        } else {
            if (homeConfig.getHomeNormal() == 0) return true;
            return getHomeList(player.getUniqueId()).size() < homeConfig.getHomeNormal();
        }
    }
}
