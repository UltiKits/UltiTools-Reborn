package com.ultikits.plugins.joinwelcome.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class Config extends AbstractConfigEntity {

    @ConfigEntry(path = "sendMessageDelay", comment = "x秒后发送入服欢迎")
    private int sendMessageDelay;
    @ConfigEntry(path = "welcome_message", comment = "入服欢迎语")
    private List<String> welcomeMessage;
    @ConfigEntry(path = "op_join", comment = "op入服提示语")
    private String opJoin;
    @ConfigEntry(path = "op_quit", comment = "op下线提示语")
    private String opQuit;
    @ConfigEntry(path = "player_join", comment = "玩家入服提示语")
    private String playerJoin;
    @ConfigEntry(path = "player_quit", comment = "玩家下线提示语")
    private String playerQuit;

    public Config(String configFilePath) {
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
