package com.ultikits.plugins.essentials.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for player join welcome messages.
 */
@Getter
@Setter
@ConfigEntity("config/welcome.yml")
public class WelcomeConfig extends AbstractConfigEntity {

    @ConfigEntry(path = "welcome.message", comment = "欢迎消息，支持 %player% 变量")
    private String message = "&6欢迎 &e%player% &6来到服务器！";

    @ConfigEntry(path = "welcome.broadcast", comment = "是否全服广播")
    private boolean broadcast = true;

    @ConfigEntry(path = "welcome.first-join-message", comment = "首次加入消息")
    private String firstJoinMessage = "&6欢迎新玩家 &e%player% &6首次加入服务器！";

    @ConfigEntry(path = "welcome.title.enabled", comment = "是否显示 Title")
    private boolean titleEnabled = true;

    @ConfigEntry(path = "welcome.title.main", comment = "Title 主标题")
    private String titleMain = "&6欢迎回来";

    @ConfigEntry(path = "welcome.title.sub", comment = "Title 副标题")
    private String titleSub = "&7%player%";

    public WelcomeConfig() {
        super("config/welcome.yml");
    }
}
