package com.ultikits.plugins.home.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigEntity("config/config.yml")
public class HomeConfig extends AbstractConfigEntity {
    @ConfigEntry(path = "home_normal", comment = "默认玩家权限可以创建的家数量")
    private int homeNormal = 3;
    @ConfigEntry(path = "home_pro", comment = "pro权限可以创建的家数量")
    private int homePro = 5;
    @ConfigEntry(path = "home_ultimate", comment = "ultimate权限可以创建的家数量")
    private int homeUltimate = 10;
    @ConfigEntry(path = "home_tpwait", comment = "家传送等待时间（秒）")
    private int homeTpWait = 3;

    public HomeConfig(String configFilePath) {
        super(configFilePath);
    }

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
