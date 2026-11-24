package com.ultikits.ultitools.command.engine;

import java.lang.reflect.Method;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import com.ultikits.ultitools.abstracts.command.validation.CmdTargetComposition;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/** Side-effect-free visibility checks; execution still runs the executor's validator chain. */
final class CommandAccess {
    private CommandAccess() { /* Static visibility policy. */ }

    static boolean root(String permission, boolean requireOp, CommandSourceStack source) {
        return permission(permission, requireOp, source.getSender());
    }

    static boolean mapping(Method method, CommandSourceStack source) {
        CmdMapping mapping = method.getAnnotation(CmdMapping.class);
        return permission(mapping.permission(), mapping.requireOp(), source.getSender());
    }

    static boolean suggestible(Class<?> executor, Method method, CommandSourceStack source) {
        return target(CmdTargetComposition.resolve(classTarget(executor), method), source.getSender())
                && mapping(method, source);
    }

    static boolean classVisible(Class<?> executor, CommandSourceStack source) {
        return target(classTarget(executor), source.getSender());
    }

    static CmdTarget.CmdTargetType classTarget(Class<?> executor) {
        CmdTarget annotation = MergedAnnotationResolver.find(executor, CmdTarget.class);
        return annotation == null ? CmdTarget.CmdTargetType.BOTH : annotation.value();
    }

    private static boolean permission(String permission, boolean requireOp, CommandSender sender) {
        return (!requireOp || sender.isOp()) && (permission == null || permission.isEmpty() || sender.hasPermission(permission));
    }

    private static boolean target(CmdTarget.CmdTargetType target, CommandSender sender) {
        return switch (target) {
            case BOTH -> true;
            case PLAYER -> sender instanceof Player;
            case CONSOLE -> !(sender instanceof Player);
        };
    }
}
