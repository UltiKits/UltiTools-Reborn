package com.ultikits.plugins.basic.utils;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.plugins.basic.config.WarpConfig;
import com.ultikits.plugins.basic.data.WarpData;
import com.ultikits.ultitools.entities.common.WorldLocation;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;

import java.util.List;

import static com.ultikits.ultitools.abstracts.UltiToolsPlugin.getConfigManager;

public class BlueMapUtils {

    public static void initBlueMap(List<WarpData> warps) {
        BlueMapAPI.onEnable(api -> {
            WarpConfig warpConfig = getConfigManager().getConfigEntity(BasicFunctions.getInstance(), WarpConfig.class);
            if (!warpConfig.isEnableBlueMap()) {
                return;
            }
            api.getWorlds().forEach(world -> {
                for (BlueMapMap map : world.getMaps()) {
                    MarkerSet markerSet = MarkerSet.builder()
                            .label(warpConfig.getMarkerSet())
                            .build();
                    map.getMarkerSets().put(warpConfig.getMarkerSet(), markerSet);
                }
            });
            for (WarpData warpData : warps) {
                addWarpToBlueMap(warpData);
            }
        });
    }

    public static void addWarpToBlueMap(WarpData warpData) {
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

    public static void removeWarpFromBlueMap(WarpData warpData) {
        WarpConfig warpConfig = getConfigManager().getConfigEntity(BasicFunctions.getInstance(), WarpConfig.class);
        if (!warpConfig.isEnableBlueMap()) {
            return;
        }
        BlueMapAPI.getInstance().flatMap(api -> api.getWorld(warpData.getLocation().getWorld())).ifPresent(world -> {
            for (BlueMapMap map : world.getMaps()) {
                MarkerSet markerSet = map.getMarkerSets().get(warpConfig.getMarkerSet());
                markerSet.remove(String.valueOf(warpData.getId()));
            }
        });
    }
}
