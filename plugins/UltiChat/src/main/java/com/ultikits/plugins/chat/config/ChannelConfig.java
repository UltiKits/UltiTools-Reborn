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
@ConfigEntity("config/channels.yml")
public class ChannelConfig extends AbstractConfigEntity {

    @ConfigEntry(path = "channels.enabled", comment = "Enable channel system / 启用频道系统")
    private boolean enabled = true;

    @ConfigEntry(path = "channels.default-channel", comment = "Default channel for new players / 默认频道")
    private String defaultChannel = "global";

    @ConfigEntry(path = "channels.channels", comment = "Channel definitions / 频道定义")
    private Map<String, Map<String, Object>> channels = new HashMap<String, Map<String, Object>>() {{
        HashMap<String, Object> global = new HashMap<>();
        global.put("display-name", "&f[Global]");
        global.put("format", "{display}&f: {message}");
        global.put("permission", "");
        global.put("range", -1);
        global.put("cross-world", true);
        put("global", global);

        HashMap<String, Object> local = new HashMap<>();
        local.put("display-name", "&a[Local]");
        local.put("format", "{display}&7: {message}");
        local.put("permission", "");
        local.put("range", 100);
        local.put("cross-world", false);
        put("local", local);

        HashMap<String, Object> staff = new HashMap<>();
        staff.put("display-name", "&c[Staff]");
        staff.put("format", "&c[Staff] &f{player}&7: {message}");
        staff.put("permission", "ultichat.channel.staff");
        staff.put("range", -1);
        staff.put("cross-world", true);
        put("staff", staff);
    }};

    public ChannelConfig() {
        super("config/channels.yml");
    }
}
