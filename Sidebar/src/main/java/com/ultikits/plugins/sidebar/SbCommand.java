package com.ultikits.plugins.sidebar;

import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;
import fr.mrmicky.fastboard.FastBoard;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static com.ultikits.ultitools.utils.MessageUtils.coloredMsg;

@CmdExecutor(alias = {"sb"})
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
public class SbCommand extends AbstractCommendExecutor {

    @CmdMapping(format = "close")
    public void close(@CmdSender Player sender) {
        SidebarPlugin.getInstance().getBoards().get(sender.getUniqueId()).delete();
        SidebarPlugin.getInstance().getBoards().remove(sender.getUniqueId());
    }

    @CmdMapping(format = "reload", requireOp = true)
    public void reload() {
        SidebarPlugin.getInstance().registerSelf();
    }

    @CmdMapping(format = "open")
    public void open(@CmdSender Player player) {
        FastBoard board = new FastBoard(player);
        board.updateTitle(coloredMsg(SidebarPlugin.getInstance().getConfig(SidebarConfig.class).getTitle()));
        SidebarPlugin.getInstance().getBoards().put(player.getUniqueId(), board);
    }

    @Override
    protected void handleHelp(CommandSender sender) {

    }
}
