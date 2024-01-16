package com.ultikits.plugins.sidebar;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigEntity("config/config.yml")
public class SidebarConfig extends AbstractConfigEntity {
    @ConfigEntry(path = "scoreBoardTitle", comment = "侧边栏标题")
    private String title = "欢迎加入服务器";
    @ConfigEntry(path = "scoreBoardUpdateInterval", comment = "侧边栏更新间隔（单位：Tick）推荐20（每秒更新一次），最低1，更低会导致性能问题")
    private int updateInterval = 20;
    @ConfigEntry(path = "sidebarContent", comment = "侧边栏内容（最多15行）")
    private List<String> content = new ArrayList<>();

    public SidebarConfig(String configFilePath) {
        super(configFilePath);
    }
}
