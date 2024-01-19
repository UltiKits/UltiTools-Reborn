package com.ultikits.plugins.basic.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigEntity("config/warp.yml")
public class WarpConfig extends AbstractConfigEntity {
    @ConfigEntry(path = "enable_blue_map", comment = "是否启用BlueMap")
    private boolean enableBlueMap = false;
    @ConfigEntry(path = "blue_map_warp_max_distance", comment = "传送点在BlueMap中最远显示距离")
    private int maxDistance = 1000;
    @ConfigEntry(path = "blue_map_marker_set", comment = "传送点在BlueMap中的标记集名称")
    private String markerSet = "UltiTools Warps";

    public WarpConfig(String configFilePath) {
        super(configFilePath);
    }
}
