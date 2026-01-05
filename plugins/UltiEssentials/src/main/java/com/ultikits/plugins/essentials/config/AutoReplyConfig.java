package com.ultikits.plugins.essentials.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for auto-reply feature.
 */
@Getter
@Setter
@ConfigEntity("config/autoreply.yml")
public class AutoReplyConfig extends AbstractConfigEntity {

    @ConfigEntry(path = "autoreply.rules", comment = "自动回复规则 (关键词: 回复内容)")
    private Map<String, String> rules = new HashMap<String, String>() {{
        put("服务器IP", "服务器地址: play.example.com");
        put("管理员", "请联系管理员: admin@example.com");
        put("规则", "请查看服务器规则: /rules");
    }};

    @ConfigEntry(path = "autoreply.case-sensitive", comment = "是否区分大小写")
    private boolean caseSensitive = false;

    @ConfigEntry(path = "autoreply.cooldown", comment = "同一玩家触发冷却时间(秒)")
    private int cooldown = 10;

    public AutoReplyConfig() {
        super("config/autoreply.yml");
    }
}
