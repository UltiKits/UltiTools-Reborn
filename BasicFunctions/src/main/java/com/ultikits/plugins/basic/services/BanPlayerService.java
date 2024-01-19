package com.ultikits.plugins.basic.services;

import cn.hutool.core.date.LocalDateTimeUtil;
import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.plugins.basic.data.BanedUserData;
import com.ultikits.ultitools.interfaces.DataOperator;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class BanPlayerService {

    public boolean isBaned(Player player) {
        DataOperator<BanedUserData> dataOperator = BasicFunctions.getInstance().getDataOperator(BanedUserData.class);
        BanedUserData banedUserData = dataOperator.getById(player.getUniqueId().toString());
        return banedUserData != null;
    }

    public void banPlayer(OfflinePlayer player, String operator, String reason) {
        DataOperator<BanedUserData> dataOperator = BasicFunctions.getInstance().getDataOperator(BanedUserData.class);
        BanedUserData banedUserData = new BanedUserData();
        banedUserData.setId(player.getUniqueId().toString());
        banedUserData.setName(player.getName());
        banedUserData.setReason(reason);
        banedUserData.setOperator(operator);
        banedUserData.setTime(LocalDateTimeUtil.format(LocalDateTimeUtil.of(new Date()), "yyyy-MM-dd HH:mm:ss"));
        dataOperator.insert(banedUserData);
    }

    public void unBanPlayer(OfflinePlayer player) {
        DataOperator<BanedUserData> dataOperator = BasicFunctions.getInstance().getDataOperator(BanedUserData.class);
        dataOperator.delById(player.getUniqueId().toString());
    }
}
