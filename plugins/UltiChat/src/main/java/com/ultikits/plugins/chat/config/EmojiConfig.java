package com.ultikits.plugins.chat.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@ConfigEntity("config/emojis.yml")
public class EmojiConfig extends AbstractConfigEntity {

    @ConfigEntry(path = "emojis.enabled", comment = "Enable emoji shortcodes / 启用表情符号")
    private boolean enabled = true;

    @ConfigEntry(path = "emojis.mappings", comment = "Emoji shortcode mappings / 表情符号映射")
    private Map<String, String> mappings = new HashMap<String, String>() {{
        put(":heart:", "\u2764");
        put(":star:", "\u2605");
        put(":smile:", "\u263A");
        put(":sword:", "\u2694");
    }};

    public EmojiConfig() {
        super("config/emojis.yml");
    }
}
