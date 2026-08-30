package com.ultikits.ultitools.abstracts.command;

import java.lang.reflect.Method;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.command.CmdMapping;

/**
 * Single tab-completion dispatch implementation shared by both command-executor generations
 * (WIRE-01 / D-06).
 * <p>
 * {@link BaseCommandExecutor} and the deprecated
 * {@code com.ultikits.ultitools.abstracts.AbstractCommandExecutor} each scan their own
 * {@code @CmdMapping} methods into an independent mapping table and do not share a class
 * hierarchy, so neither can simply inherit a single {@code suggest} implementation from a common
 * supertype. This class is the alternative both delegate to.
 * <p>
 * As of this commit it carries only the mapping-level permission/OP guard
 * ({@link #checkPermission(CommandSender, Method)}/{@link #checkOp(CommandSender, Method)}),
 * relocated -- not copied -- from the deprecated class's private methods of the same name
 * (T-05-20 / T-05-21). {@code BaseCommandExecutor.suggest} had ZERO permission checks before this
 * class existed, so migrating a command onto the current generation leaked the entire sub-command
 * table to unprivileged players. Argument-position resolution and the full single-dispatch
 * {@code suggest(...)} entry point are added by a later plan-05-05 task.
 * <p>
 * 由两代命令执行器共用的单一 Tab 补全分发实现（WIRE-01 / D-06）。
 *
 * @author wisdomme
 * @since 6.3.0
 */
public final class CommandTabCompletionDispatch {

    private CommandTabCompletionDispatch() {
    }

    /**
     * Mapping-level permission guard: gates a matched {@code @CmdMapping} method out of tab
     * completion (or dispatch) entirely before it can contribute anything, rather than filtering
     * an already-assembled list afterwards.
     * <p>
     * Relocated -- not copied -- from the deprecated {@code AbstractCommandExecutor}'s private
     * method of the same name and signature (T-05-20). Behaviour is byte-for-byte identical,
     * including the {@code sendMessage} on denial, which both base-class generations inherit
     * unchanged from the pre-existing class this predicate was ported from.
     *
     * @param sender the command sender being checked
     * @param method the matched {@code @CmdMapping} method
     * @return {@code true} if the mapping declares no permission or the sender holds it
     */
    public static boolean checkPermission(CommandSender sender, Method method) {
        if (!method.isAnnotationPresent(CmdMapping.class)) {
            return true;
        }
        CmdMapping cmdMapping = method.getAnnotation(CmdMapping.class);
        if (cmdMapping.permission().isEmpty()) {
            return true;
        }
        String permission = cmdMapping.permission();
        if (sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage(String.format(UltiTools.getInstance().i18n("需要权限"), permission));
        return false;
    }

    /**
     * Mapping-level OP guard, the {@code requireOp()} counterpart to {@link
     * #checkPermission(CommandSender, Method)}. Relocated -- not copied -- from the deprecated
     * {@code AbstractCommandExecutor}'s private method of the same name and signature (T-05-21).
     *
     * @param sender the command sender being checked
     * @param method the matched {@code @CmdMapping} method
     * @return {@code true} if the mapping does not require OP or the sender is an OP
     */
    public static boolean checkOp(CommandSender sender, Method method) {
        if (!method.isAnnotationPresent(CmdMapping.class)) {
            return true;
        }
        CmdMapping cmdMapping = method.getAnnotation(CmdMapping.class);
        if (cmdMapping.requireOp() && !sender.isOp()) {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("你没有权限执行这个指令！"));
            return false;
        }
        return true;
    }
}
