package com.ultikits.plugins.essentials.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for server MOTD (Message of the Day).
 */
@Getter
@Setter
@ConfigEntity("config/motd.yml")
public class MotdConfig extends AbstractConfigEntity {

    @ConfigEntry(path = "motd.line1", comment = "MOTD 第一行")
    private String line1 = "&6Welcome to our server!";

    @ConfigEntry(path = "motd.line2", comment = "MOTD 第二行")
    private String line2 = "&7Powered by UltiTools";

    @ConfigEntry(path = "motd.max-players", comment = "显示的最大玩家数 (-1 使用服务器默认)")
    private int maxPlayers = -1;

    public MotdConfig() {
        super("config/motd.yml");
    }
}
