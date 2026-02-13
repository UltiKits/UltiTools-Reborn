package com.ultikits.plugins.chat.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@ConfigEntity("config/autoreply.yml")
public class AutoReplyConfig extends AbstractConfigEntity {

    @ConfigEntry(path = "autoreply.enabled", comment = "Enable auto-reply / 启用自动回复")
    private boolean enabled = true;

    @Range(min = 0, max = 300)
    @ConfigEntry(path = "autoreply.cooldown", comment = "Global cooldown between auto-replies (seconds) / 全局冷却(秒)")
    private int cooldown = 10;

    @ConfigEntry(path = "autoreply.rules", comment = "Auto-reply rules / 自动回复规则")
    private Map<String, Map<String, Object>> rules = new HashMap<String, Map<String, Object>>() {{
        HashMap<String, Object> rule1 = new HashMap<>();
        rule1.put("keyword", "server IP");
        rule1.put("response", "Server address: play.example.com");
        rule1.put("mode", "contains");
        rule1.put("case-sensitive", false);
        put("server-ip", rule1);

        HashMap<String, Object> rule2 = new HashMap<>();
        rule2.put("keyword", "rules");
        rule2.put("response", "Please check /rules for server rules.");
        rule2.put("mode", "contains");
        rule2.put("case-sensitive", false);
        put("rules-info", rule2);
    }};

    public AutoReplyConfig() {
        super("config/autoreply.yml");
    }
}
