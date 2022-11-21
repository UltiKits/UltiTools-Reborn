package com.ultikits;


import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

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
