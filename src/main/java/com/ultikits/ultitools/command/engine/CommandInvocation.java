package com.ultikits.ultitools.command.engine;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Locale;
import org.bukkit.Bukkit;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import com.ultikits.ultitools.manager.CommandExecutionManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/** Adapts parsed Brigadier values to the existing validation and execution pipeline. */
final class CommandInvocation {
    private final BaseCommandExecutor executor;
    private final String commandName;
    CommandInvocation(BaseCommandExecutor executor) { this(executor, null); }
    CommandInvocation(BaseCommandExecutor executor, String commandName) {
        this.executor = executor;
        this.commandName = commandName;
    }

    int run(CommandContext<CommandSourceStack> context, Method method, Map<String, Object> values) {
        if (!refused(context)) {
            executor.invokeTyped(context(context).withMatchedMethod(method, executor.getMappings().entrySet().stream()
                    .filter(entry -> entry.getValue().equals(method)).map(Map.Entry::getKey).findFirst().orElse(""))
                    .withParsedParams(values));
        }
        return Command.SINGLE_SUCCESS;
    }

    int help(CommandContext<CommandSourceStack> context) {
        if (!refused(context)) executor.handleGatedHelp(context(context));
        return Command.SINGLE_SUCCESS;
    }

    boolean refused(CommandContext<CommandSourceStack> context) {
        if (!CommandExecutionManager.isPanelDispatch()) return false;
        String label = rootName(context);
        var command = Bukkit.getServer().getCommandMap().getCommand(label);
        String[] args = rawArgs(context.getInput());
        String suffix = args.length == 0 ? "" : " " + String.join(" ", args);
        return CommandExecutionManager.refusePanelCommand(context.getSource().getSender(), command, label + suffix)
                || CommandExecutionManager.refusePanelCommand(context.getSource().getSender(), context.getInput());
    }

    com.ultikits.ultitools.abstracts.command.CommandContext context(CommandContext<CommandSourceStack> context) {
        String actual = context.getInput().split("\\s+", 2)[0];
        return com.ultikits.ultitools.abstracts.command.CommandContext.builder()
                .sender(context.getSource().getSender()).commandName(rootName(context)).alias(actual)
                .rawArgs(rawArgs(context.getInput())).executorClass(executor.getClass()).executor(executor).build();
    }

    private String rootName(CommandContext<CommandSourceStack> context) {
        if (commandName != null) return commandName;
        CmdExecutor annotation = MergedAnnotationResolver.find(executor.getClass(), CmdExecutor.class);
        return annotation == null ? context.getInput().split("\\s+", 2)[0] : annotation.alias()[0].toLowerCase(Locale.ROOT);
    }

    static String[] rawArgs(String input) {
        int separator = input.indexOf(' ');
        if (separator < 0) return new String[0];
        return input.substring(separator + 1).split("\\s+", -1);
    }
}
