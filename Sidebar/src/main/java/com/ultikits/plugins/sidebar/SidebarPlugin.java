package com.ultikits.plugins.sidebar;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import fr.mrmicky.fastboard.FastBoard;
import lombok.Getter;
import me.clip.placeholderapi.PlaceholderAPI;

import java.io.IOException;
import java.util.*;

import static com.ultikits.ultitools.utils.MessageUtils.coloredMsg;

public class SidebarPlugin extends UltiToolsPlugin {
    @Getter
    private static SidebarPlugin instance;
    @Getter
    private final Map<UUID, FastBoard> boards = new HashMap<>();

    @Override
    public boolean registerSelf() throws IOException {
        instance = this;
        getListenerManager().register(this, new PlayerJoinListener());
        int updateInterval = getConfig(SidebarConfig.class).getUpdateInterval();
        new UpdateTask().runTaskTimerAsynchronously(UltiTools.getInstance(), 0, Math.max(updateInterval, 1));
        return true;
    }

    @Override
    public void unregisterSelf() {
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }

    @Override
    public List<AbstractConfigEntity> getAllConfigs() {
        return Arrays.asList(
                new SidebarConfig("res/config/config.yml")
        );
    }

    protected void updateBoard(FastBoard board) {
        List<String> list = new ArrayList<>();
        for (String s : getConfig(SidebarConfig.class).getContent()) {
            list.add(coloredMsg(PlaceholderAPI.setPlaceholders(board.getPlayer(), s)));
        }
        board.updateLines(list);
    }

    @Override
    public void reloadSelf() {
        UltiToolsPlugin.getConfigManager().register(this, new SidebarConfig("res/config/config.yml"));
    }
}
