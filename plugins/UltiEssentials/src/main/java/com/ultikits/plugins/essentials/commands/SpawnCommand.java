package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.config.SpawnConfig;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Command to teleport player to the server spawn point.
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"spawn"}, permission = "ultiessentials.spawn.teleport", description = "传送到出生点")
public class SpawnCommand extends BaseEssentialsCommand {

    private final EssentialsConfig config;
    private final SpawnConfig spawnConfig;

    public SpawnCommand(EssentialsConfig config, SpawnConfig spawnConfig) {
        this.config = config;
        this.spawnConfig = spawnConfig;
    }

    @CmdMapping(format = "")
    public void teleportToSpawn(@CmdSender Player player) {
        if (!config.isSpawnEnabled()) {
            player.sendMessage(i18n("该功能已禁用"));
            return;
        }

        Location spawn = spawnConfig.getSpawnLocation();
        if (spawn.getWorld() == null) {
            player.sendMessage(i18n("出生点世界不存在"));
            return;
        }

        player.teleport(spawn);
        player.sendMessage(i18n("已传送到出生点"));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("使用 /spawn 传送到出生点"));
    }
}
