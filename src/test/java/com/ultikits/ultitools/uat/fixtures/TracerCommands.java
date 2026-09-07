package com.ultikits.ultitools.uat.fixtures;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;

import org.bukkit.command.CommandSender;

/**
 * A small {@code @CmdExecutor} fixture carrying two {@code @CmdMapping} formats, one
 * {@code @CmdParam}, one {@code @CmdSender} parameter, and one {@code @CmdCD} — so
 * {@code CommandRowScannerTest} needs no real module build to exercise the scanner (Phase 10
 * plan 10-01, Task 1).
 *
 * @since 6.3.0
 */
@CmdExecutor(alias = {"tracer"}, permission = "uat.tracer", description = "Tracer fixture command")
public class TracerCommands extends BaseCommandExecutor {

    @CmdMapping(format = "ping")
    @CmdCD(5)
    public void onPing(@CmdSender CommandSender sender) {
        sender.sendMessage("pong");
    }

    @CmdMapping(format = "echo <message>")
    public void onEcho(@CmdSender CommandSender sender, @CmdParam("message") String message) {
        sender.sendMessage(message);
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("tracer help");
    }
}
