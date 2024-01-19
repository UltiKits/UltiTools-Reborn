package com.ultikits.plugins.basic.commands;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Random;

import static com.ultikits.ultitools.utils.MessageUtils.info;
import static com.ultikits.ultitools.utils.MessageUtils.warning;

@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"wild"}, manualRegister = true, permission = "ultikits.tools.command.wild", description = "随机传送功能")
public class RandomTpCommands extends AbstractCommendExecutor {

    @CmdMapping(format = "")
    public void wild(@CmdSender Player player) {
        World.Environment environment = player.getWorld().getEnvironment();
        if (environment == World.Environment.NETHER || environment == World.Environment.THE_END) {
            player.sendMessage(warning(BasicFunctions.getInstance().i18n("这个世界禁止使用随机传送！")));
        } else {
            new BukkitRunnable() {
                @Override
                public void run() {
                    World world = player.getWorld();
                    // 在此世界循环搜索可以安全传送的位置
                    while (true) {
                        player.sendMessage(info(BasicFunctions.getInstance().i18n("随机传送中...")));
                        int randomX = new Random().nextInt(1600) - 800;
                        int randomZ = new Random().nextInt(1600) - 800;
                        Block block = world.getHighestBlockAt(randomX, randomZ);   //得到随机点最高不可通过方块
                        Location location = new Location(world, block.getLocation().getX(), block.getLocation().getY() + 1, block.getLocation().getZ());  //方块上方，即传送点
                        Location location2 = new Location(world, block.getLocation().getX(), block.getLocation().getY() + 2, block.getLocation().getZ());
                        Block blockY1 = world.getBlockAt(location);    //方块上方，为传送点
                        Block blockY2 = world.getBlockAt(location2);   //方块上方第二个，为了检测Y1（传送点）
                        if (blockY2.getRelative(BlockFace.DOWN).getType() == Material.WATER ||
                                blockY2.getRelative(BlockFace.DOWN).getType() == Material.LAVA ||
                                blockY2.getRelative(BlockFace.DOWN).getType() == Material.WATER) {
                            continue;
                        }
                        world.getChunkAt(location).load();
                        player.teleport(location);
                        player.sendMessage(info(BasicFunctions.getInstance().i18n("随机传送成功！")));
                        return;
                    }
                }
            }.runTask(UltiTools.getInstance());
        }
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(info(BasicFunctions.getInstance().i18n("随机传送到一个安全的地方")));
    }
}

