package com.ultikits.plugins.menu.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigEntity("config/config.yml")
public class MenuConfig extends AbstractConfigEntity {
    @ConfigEntry(path = "click_cooldown_ms", comment = "Global click debounce in milliseconds / 全局点击防抖毫秒数")
    @Range(min = 50, max = 5000)
    private int clickCooldownMs = 200;

    public MenuConfig(String configFilePath) {
        super(configFilePath);
    }
}
