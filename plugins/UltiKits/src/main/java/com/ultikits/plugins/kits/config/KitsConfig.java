package com.ultikits.plugins.kits.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigEntity("config/config.yml")
public class KitsConfig extends AbstractConfigEntity {

    @ConfigEntry(path = "enabled", comment = "Enable kit system / 启用礼包系统")
    private boolean enabled = true;

    @ConfigEntry(path = "click_cooldown_ms", comment = "GUI click debounce ms / GUI点击防抖毫秒数")
    @Range(min = 50, max = 5000)
    private int clickCooldownMs = 200;

    @ConfigEntry(path = "kits_per_page", comment = "Kits per page in browser GUI / 每页礼包数量")
    @Range(min = 7, max = 28)
    private int kitsPerPage = 28;

    public KitsConfig(String configFilePath) {
        super(configFilePath);
    }
}
