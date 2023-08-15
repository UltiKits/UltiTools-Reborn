package com.ultikits.plugins.sidebar;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class SidebarConfig extends AbstractConfigEntity {
    @ConfigEntry(path = "scoreBoardTitle", comment = "")
    private String title = "欢迎加入服务器";
    @ConfigEntry(path = "scoreBoardUpdateInterval", comment = "")
    private int updateInterval = 20;
    @ConfigEntry(path = "sidebarContent", comment = "")
    private List<String> content = new ArrayList<>();

    public SidebarConfig(String configFilePath) {
        super(configFilePath);
    }
}
