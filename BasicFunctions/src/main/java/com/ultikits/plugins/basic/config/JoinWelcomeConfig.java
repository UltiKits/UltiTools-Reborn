package com.ultikits.plugins.basic.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigEntity("config/join.yml")
public class JoinWelcomeConfig extends AbstractConfigEntity {

    @ConfigEntry(path = "sendMessageDelay", comment = "x秒后发送入服欢迎")
    private int sendMessageDelay = 4;
    @ConfigEntry(path = "welcome_message", comment = "入服欢迎语")
    private List<String> welcomeMessage = new ArrayList<>();
    @ConfigEntry(path = "op_join", comment = "op入服提示语")
    private String opJoin = "&c[管理员] &e%player_name% &c已上线";
    @ConfigEntry(path = "op_quit", comment = "op下线提示语")
    private String opQuit = "&c[管理员] &e%player_name% &c已下线";
    @ConfigEntry(path = "player_join", comment = "玩家入服提示语")
    private String playerJoin = "&c[玩家] &e%player_name% &c已上线";
    @ConfigEntry(path = "player_quit", comment = "玩家下线提示语")
    private String playerQuit = "&c[玩家] &e%player_name% &c已下线";

    public JoinWelcomeConfig(String configFilePath) {
        super(configFilePath);
    }

    @Override
    public String toString() {
        return "{"
                + "\"sendMessageDelay\":"
                + sendMessageDelay
                + ",\"welcomeMessage\":"
                + welcomeMessage
                + ",\"opJoin\":\""
                + opJoin + '\"'
                + ",\"opQuit\":\""
                + opQuit + '\"'
                + ",\"playerJoin\":\""
                + playerJoin + '\"'
                + ",\"playerQuit\":\""
                + playerQuit + '\"'
                + "}";
    }
}
