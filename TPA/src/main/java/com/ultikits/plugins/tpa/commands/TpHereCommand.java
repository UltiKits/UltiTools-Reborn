package com.ultikits.plugins.tpa.commands;

import com.ultikits.abstracts.AbstractTabExecutor;
import com.ultikits.plugins.tpa.Tpa;
import com.ultikits.plugins.tpa.services.TpaService;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.utils.MessagesUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

public class TpHereCommand extends AbstractTabExecutor {
    private static final TpaService tpaService;

    static {
        Optional<TpaService> service = PluginManager.getService(TpaService.class);
        if (!service.isPresent()){
            throw new RuntimeException("TPA Service Not Found!");
        }
        tpaService = service.get();
    }

    @Override
    protected boolean onPlayerCommand(Command command, String[] strings, Player player) {
        switch (strings.length) {
            case 1:
                switch (strings[0]) {
                    case "accept":
                        try {
                            tpaService.acceptTpa(player);
                            tpaService.getOther(player).sendMessage(MessagesUtils.info(Tpa.getInstance().i18n("tpa_teleport_success")));
                        } catch (UnsupportedOperationException ignored) {
                            player.sendMessage(MessagesUtils.warning(Tpa.getInstance().i18n("tpa_no_request")));
                        }
                        return true;
                    case "reject":
                        if (tpaService.isPlayerIsRequested(player)) {
                            player.sendMessage(MessagesUtils.info(Tpa.getInstance().i18n("tpa_rejected")));
                            tpaService.getOther(player).sendMessage(MessagesUtils.warning(Tpa.getInstance().i18n("tpa_teleport_rejected")));
                            tpaService.rejectTpa(player, false);
                        } else {
                            player.sendMessage(MessagesUtils.warning(Tpa.getInstance().i18n("tpa_no_request")));
                        }
                        return true;
                    default:
                        Player target = Bukkit.getPlayerExact(strings[0]);
                        if (target == null) {
                            player.sendMessage(MessagesUtils.warning(Tpa.getInstance().i18n("tpa_player_not_found")));
                            return true;
                        }
                        if (tpaService.isPlayerInTemp(target)) {
                            player.sendMessage(MessagesUtils.warning(Tpa.getInstance().i18n("tpa_target_busy")));
                            return true;
                        }
                        tpaService.requestTpa(player, target, target);
                        player.sendMessage(MessagesUtils.info(String.format(Tpa.getInstance().i18n("tpa_tp_send_successfully"), target.getName())));
                        target.sendMessage(MessagesUtils.info(String.format(Tpa.getInstance().i18n("tpahere_enquire"), player.getName())));
                        target.sendMessage(MessagesUtils.info(Tpa.getInstance().i18n("tphere_accept_tip")));
                        target.sendMessage(MessagesUtils.info(Tpa.getInstance().i18n("tphere_reject_tip")));
                        return true;
                }
            default:
                return false;
        }
    }

    @Nullable
    @Override
    protected List<String> onPlayerTabComplete(Command command, String[] strings, Player player) {
        return tpaService.getTpTabList(strings);
    }
}
