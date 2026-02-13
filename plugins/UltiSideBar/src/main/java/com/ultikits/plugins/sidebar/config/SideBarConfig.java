package com.ultikits.plugins.sidebar.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.NotEmpty;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.annotations.config.Size;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for UltiSideBar.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Getter
@Setter
@ConfigEntity("config/sidebar.yml")
public class SideBarConfig extends AbstractConfigEntity {

    @ConfigEntry(path = "enabled", comment = "启用侧边栏")
    private boolean enabled = true;

    @NotEmpty
    @Size(min = 1, max = 32)
    @ConfigEntry(path = "title", comment = "侧边栏标题（支持颜色代码和变量）")
    private String title = "&6&l我的服务器";

    @Range(min = 1, max = 1200)
    @ConfigEntry(path = "update-interval", comment = "更新间隔（tick，20 tick = 1秒）")
    private int updateInterval = 20;

    @NotEmpty
    @Size(min = 1, max = 15)
    @ConfigEntry(path = "lines", comment = "侧边栏内容（支持 PlaceholderAPI 变量）")
    private List<String> lines = Arrays.asList(
        "&7欢迎, &f%player_name%",
        "",
        "&e在线人数: &f%server_online%/%server_max_players%",
        "&e世界: &f%world_name%",
        "",
        "&e金币: &f%vault_eco_balance_formatted%",
        "&ePing: &f%player_ping%ms",
        "",
        "&7服务器时间",
        "&f%server_time_hh:mm:ss%",
        "",
        "&6play.example.com"
    );

    @ConfigEntry(path = "world-blacklist", comment = "禁用侧边栏的世界")
    private List<String> worldBlacklist = Collections.singletonList("world_event");

    @ConfigEntry(path = "default-enabled", comment = "玩家默认启用侧边栏")
    private boolean defaultEnabled = true;

    public SideBarConfig() {
        super("config/sidebar.yml");
    }
}
