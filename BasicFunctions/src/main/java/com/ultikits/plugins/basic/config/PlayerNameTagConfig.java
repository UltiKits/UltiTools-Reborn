package com.ultikits.plugins.basic.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigEntity("config/title.yml")
public class PlayerNameTagConfig extends AbstractConfigEntity {
    @ConfigEntry(path = "prefix", comment = "玩家名字前缀")
    private String prefix = "&e[&dLv.%player_level%&e]";
    @ConfigEntry(path = "suffix", comment = "玩家名字后缀")
    private String suffix = "&e[&a%player_health_rounded%&e/&a%player_max_health_rounded%&e]";
    @ConfigEntry(path = "updateInterval", comment = "更新间隔")
    private int updateInterval = 20;
    @ConfigEntry(path = "enableNameTagEdit", comment = "是否启用NameTagEdit支持")
    private boolean enableNameTagEdit = false;

    public PlayerNameTagConfig(String configFilePath) {
        super(configFilePath);
    }
}
