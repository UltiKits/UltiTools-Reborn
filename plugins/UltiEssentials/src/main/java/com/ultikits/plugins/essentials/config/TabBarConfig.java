package com.ultikits.plugins.essentials.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for player tab list header and footer.
 */
@Getter
@Setter
@ConfigEntity("config/tabbar.yml")
public class TabBarConfig extends AbstractConfigEntity {

    @ConfigEntry(path = "tabbar.header", comment = "Tab 栏头部")
    private String header = "&6=== 服务器名称 ===";

    @ConfigEntry(path = "tabbar.footer", comment = "Tab 栏底部，支持 %online% %max% 变量")
    private String footer = "&7在线: &e%online%&7/&e%max%";

    public TabBarConfig() {
        super("config/tabbar.yml");
    }
}
