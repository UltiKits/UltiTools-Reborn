package com.ultikits.plugins.sidebar;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import fr.mrmicky.fastboard.FastBoard;
import lombok.Getter;
import me.clip.placeholderapi.PlaceholderAPI;

import java.io.IOException;
import java.util.*;

import static com.ultikits.ultitools.utils.MessageUtils.coloredMsg;

@UltiToolsModule
public class SidebarPlugin extends UltiToolsPlugin {
    @Getter
    private static SidebarPlugin instance;
    @Getter
    private final Map<UUID, FastBoard> boards = new HashMap<>();

    public SidebarPlugin(){
        super();
        instance = this;
    }

    @Override
    public boolean registerSelf() {
        int updateInterval = getConfig(SidebarConfig.class).getUpdateInterval();
        new UpdateTask().runTaskTimerAsynchronously(UltiTools.getInstance(), 0, Math.max(updateInterval, 1));
        return true;
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }

    @Override
    public List<AbstractConfigEntity> getAllConfigs() {
        return Arrays.asList(
                new SidebarConfig("config/config.yml")
        );
    }
}
