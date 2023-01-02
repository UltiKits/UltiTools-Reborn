package com.ultikits.plugins.home.config;

import com.ultikits.ultitools.abstracts.ConfigEntity;
import com.ultikits.ultitools.annotations.Config;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Config(name = "config", filePath = "res/config/config.yml")
public class HomeConfig extends ConfigEntity {
    @ConfigEntry(path = "home_normal", comment = "")
    private int homeNormal = 3;
    @ConfigEntry(path = "home_pro", comment = "")
    private int homePro = 5;
    @ConfigEntry(path = "home_ultimate", comment = "")
    private int homeUltimate = 10;
    @ConfigEntry(path = "home_tpwait", comment = "")
    private int homeTpWait = 3;

    @Override
    public String toString() {
        return "{"
                + "\"homeNormal\":"
                + homeNormal
                + ",\"homePro\":"
                + homePro
                + ",\"homeUltimate\":"
                + homeUltimate
                + ",\"homeTpWait\":"
                + homeTpWait
                + "},\"super-HomeConfig\":" + super.toString() + "}";
    }
}
