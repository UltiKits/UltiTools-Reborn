package com.ultikits.ultitools.uat.fixtures;

import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;

import org.bukkit.command.CommandSender;

/**
 * Carries {@code @CmdExecutor} and a structurally valid {@code @CmdMapping} method, but
 * deliberately does NOT implement {@code org.bukkit.command.CommandExecutor} -- the annotation
 * itself enforces no such supertype, but {@code CommandManager.registerAll} discovers commands
 * exclusively through {@code getBeanNamesForType(CommandExecutor.class)}, so this bean is never
 * returned by that lookup and its command can never register (Codex review of PR #427).
 *
 * @since 6.3.0
 */
@CmdExecutor(alias = {"notacommandexecutor"})
public class AnnotatedButNotACommandExecutor {

    @CmdMapping(format = "go")
    public void go(@CmdSender CommandSender sender) {
        sender.sendMessage("go");
    }
}
