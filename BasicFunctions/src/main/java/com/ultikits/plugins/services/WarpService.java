package com.ultikits.plugins.services;

import com.ultikits.plugins.BasicFunctions;
import com.ultikits.plugins.config.WarpConfig;
import com.ultikits.plugins.data.WarpData;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.entities.common.WorldLocation;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
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
        addWarpToBlueMap(warpData);
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
        removeWarpFromBlueMap(warpData.get(0));
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

    public void initBlueMap(){
        WarpConfig warpConfig = getConfigManager().getConfigEntity(BasicFunctions.getInstance(), WarpConfig.class);
        if (!warpConfig.isEnableBlueMap()) {
            return;
        }
        BlueMapAPI.getInstance().ifPresent(api -> {
            api.getWorlds().forEach(world -> {
                for (BlueMapMap map : world.getMaps()) {
                    MarkerSet markerSet = MarkerSet.builder()
                            .label(warpConfig.getMarkerSet())
                            .build();
                    map.getMarkerSets().put(warpConfig.getMarkerSet(), markerSet);
                }
            });
        });
        for (WarpData warpData : getAllWarps()) {
            addWarpToBlueMap(warpData);
        }
    }

    public void addWarpToBlueMap(WarpData warpData) {
        WarpConfig warpConfig = getConfigManager().getConfigEntity(BasicFunctions.getInstance(), WarpConfig.class);
        if (!warpConfig.isEnableBlueMap()) {
            return;
        }
        WorldLocation location = warpData.getLocation();
        POIMarker marker = POIMarker.builder()
                .label(warpData.getName())
                .position(location.getX(), location.getY(), location.getZ())
                .maxDistance(warpConfig.getMaxDistance())
                .build();

        BlueMapAPI.getInstance().flatMap(api -> api.getWorld(location.getWorld())).ifPresent(world -> {
            for (BlueMapMap map : world.getMaps()) {
                MarkerSet markerSet = map.getMarkerSets().get(warpConfig.getMarkerSet());
                markerSet.getMarkers().put(String.valueOf(warpData.getId()), marker);
                map.getMarkerSets().put(warpConfig.getMarkerSet(), markerSet);
            }
        });
    }

    public void removeWarpFromBlueMap(WarpData warpData) {
        WarpConfig warpConfig = getConfigManager().getConfigEntity(BasicFunctions.getInstance(), WarpConfig.class);
        if (!warpConfig.isEnableBlueMap()) {
            return;
        }
        BlueMapAPI.getInstance().flatMap(api -> api.getWorld(warpData.getLocation().getWorld())).ifPresent(world -> {
            for (BlueMapMap map : world.getMaps()) {
                MarkerSet markerSet = map.getMarkerSets().get(warpConfig.getMarkerSet());
                markerSet.remove(String.valueOf(warpData.getId()));
//                map.getMarkerSets().put(warpConfig.getMarkerSet(), markerSet);
            }
        });
    }
}
