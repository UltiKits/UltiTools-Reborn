package com.ultikits.plugins.basic.services;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.plugins.basic.config.WarpConfig;
import com.ultikits.plugins.basic.data.WarpData;
import com.ultikits.plugins.basic.utils.BlueMapUtils;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.entities.common.WorldLocation;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.ultikits.ultitools.abstracts.UltiToolsPlugin.getConfigManager;

@Service
public class WarpService {

    public static Location toLocation(WorldLocation worldLocation) {
        return new Location(Bukkit.getWorld(worldLocation.getWorld()), worldLocation.getX(), worldLocation.getY(), worldLocation.getZ(), worldLocation.getYaw(), worldLocation.getPitch());
    }

    public void addWarp(String name, Location location) {
        WarpData warpData = new WarpData();
        warpData.setName(name);
        WorldLocation worldLocation = new WorldLocation(location);
        warpData.setLocation(worldLocation);
        BasicFunctions.getInstance().getDataOperator(WarpData.class).insert(warpData);
        if (!getConfigManager().getConfigEntity(BasicFunctions.getInstance(), WarpConfig.class).isEnableBlueMap()) {
            return;
        }
        BlueMapUtils.addWarpToBlueMap(warpData);
    }

    public void removeWarp(String name) {
        List<WarpData> warpData = BasicFunctions.getInstance().getDataOperator(WarpData.class).getAll(
                WhereCondition.builder().column("name").value(name).build()
        );
        if (warpData.isEmpty()) {
            return;
        }
        BasicFunctions.getInstance().getDataOperator(WarpData.class).del(
                WhereCondition.builder().column("name").value(name).build()
        );
        if (!getConfigManager().getConfigEntity(BasicFunctions.getInstance(), WarpConfig.class).isEnableBlueMap()) {
            return;
        }
        BlueMapUtils.removeWarpFromBlueMap(warpData.get(0));
    }

    public Location getWarpLocation(String name) {
        List<WarpData> warpData = BasicFunctions.getInstance().getDataOperator(WarpData.class).getAll(
                WhereCondition.builder().column("name").value(name).build()
        );
        if (warpData.isEmpty()) {
            return null;
        }
        return toLocation(warpData.get(0).getLocation());
    }

    public List<WarpData> getAllWarps() {
        return BasicFunctions.getInstance().getDataOperator(WarpData.class).getAll();
    }

}
