package com.ultikits.plugins.basic.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.interfaces.impl.pasers.StringHashMapParser;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@ConfigEntity("config/chat.yml")
public class ChatConfig extends AbstractConfigEntity {
    @ConfigEntry(path = "chat_prefix", comment = "聊天前缀")
    private String chatPrefix = "&e[&a%player_world%&e][&b%player_name%&e]";
    @ConfigEntry(path = "auto_reply", comment = "自动回复", parser = StringHashMapParser.class)
    private Map<String, String> autoReply = new HashMap<>();

    /**
     * Constructor for AbstractConfigEntity.
     * <p>
     * AbstractConfigEntity的构造函数。
     *
     * @param configFilePath the path to the configuration file, for example: config/config.yml <br> 配置文件在resource文件夹的路径，例如：config/config.yml
     */
    public ChatConfig(String configFilePath) {
        super(configFilePath);
    }
}
