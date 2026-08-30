package com.ultikits.ultitools.abstracts.command;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.google.common.collect.BiMap;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.commands.tabcomplete.TabCompletionContext;
import com.ultikits.ultitools.commands.tabcomplete.TabCompletionManager;

/**
 * Single tab-completion dispatch implementation shared by both command-executor generations
 * (WIRE-01 / D-06).
 * <p>
 * {@link BaseCommandExecutor} and the deprecated
 * {@code com.ultikits.ultitools.abstracts.AbstractCommandExecutor} each scan their own
 * {@code @CmdMapping} methods into an independent {@code BiMap<String, Method>} and do not share
 * a class hierarchy, so neither can simply inherit a single {@code suggest} implementation from a
 * common supertype. This class is the alternative both delegate to: a stateless dispatch entered
 * with the caller's own mapping table and {@code this}.
 * <p>
 * Two responsibilities live here that were previously either duplicated or entirely absent:
 * <ul>
 *     <li>The mapping-level {@code @CmdMapping(permission=)}/{@code requireOp()} guard
 *     ({@link #checkPermission(CommandSender, Method)}/{@link #checkOp(CommandSender, Method)}),
 *     relocated -- not copied -- from the deprecated class's private methods of the same name
 *     (T-05-20 / T-05-21). {@code BaseCommandExecutor.suggest} had ZERO permission checks before
 *     this class existed, so migrating a command onto the current generation leaked the entire
 *     sub-command table to unprivileged players.</li>
 *     <li>Argument-index -&gt; matched-method -&gt; parameter-name resolution for a multi-token
 *     argument vector ({@link #suggest(BiMap, Player, Command, String[], Object)}), which is
 *     absent from {@code commands/tabcomplete/TabCompletionManager} (its {@code createContext}
 *     leaves {@code matchedMethod}/{@code parameterName} null).</li>
 * </ul>
 * Reflective suggestion-method invocation itself is deliberately NOT reimplemented here -- the
 * resolved {@code <param>} slot is handed to
 * {@link TabCompletionManager#suggest(TabCompletionContext)}, which reaches
 * {@code MethodInvocationCompleter}. That completer walks the class hierarchy (issue #190), so an
 * AOP-proxied executor's suggestion method resolves correctly; the deprecated class's own eight
 * private reflection helpers did not.
 * <p>
 * {@code @key} notation (a {@code suggest()} value starting with {@code @}) resolves through
 * {@link TabCompletionManager#resolveSuggestValue(Method, String)} +
 * {@link TabCompletionManager#suggest(TabCompletionContext, String)} (05-06 / D-07) -- driven by
 * the parameter's actual {@code @CmdParam.suggest()}, never by {@code parameterName} (the
 * display name), so a display name that happens to start with {@code @} is never mistaken for a
 * completer key.
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
     * The single tab-completion dispatch entry point for both base-class generations.
     * <p>
     * Both generations' {@code suggest(Player, Command, String[])} reduce to a one-line
     * delegation to this method -- see {@link BaseCommandExecutor#suggest(Player, Command,
     * String[])} and the deprecated class's own shell.
     *
     * @param mappings         the executor's own format-to-method mapping table
     * @param player           the player requesting completion
     * @param command          the command being completed
     * @param args             the current arguments, including the partial final token
     * @param executorInstance the executor instance ({@code this} from the caller) -- carried
     *                         through to {@link TabCompletionContext} for reflective suggestion
     *                         invocation
     * @return the suggestions; never null
     */
    public static List<String> suggest(BiMap<String, Method> mappings, Player player, Command command,
                                        String[] args, Object executorInstance) {
        if (args == null || args.length == 0) {
            return new ArrayList<>();
        }
        if (args.length == 1) {
            return suggestFirstToken(mappings, player, command, args);
        }
        return suggestSubsequentToken(mappings, player, command, args, executorInstance);
    }

    /**
     * First-token completion: literal command formats, permission-filtered before they enter the
     * candidate map, then delegated to {@link TabCompletionManager#suggestFirstArgs(Map,
     * TabCompletionContext)} for the package's existing sort-and-dedup semantics. The deprecated
     * class's own first-token branch neither sorted nor deduplicated -- adopting the package's
     * behaviour here is a deliberate, named change (see plan 05-05's SUMMARY).
     */
    private static List<String> suggestFirstToken(BiMap<String, Method> mappings, Player player,
                                                    Command command, String[] args) {
        Map<String, Method> visible = new LinkedHashMap<>();
        for (Map.Entry<String, Method> entry : mappings.entrySet()) {
            if (isVisible(player, entry.getValue())) {
                visible.put(entry.getKey(), entry.getValue());
            }
        }
        TabCompletionManager manager = TabCompletionManager.getInstance();
        TabCompletionContext context = manager.createContext(player, command, args);
        return manager.suggestFirstArgs(visible, context);
    }

    /**
     * Argument-position resolution for a multi-token vector: find every mapping whose format's
     * literal/parameter prefix matches the already-typed tokens (permission-filtered), then for
     * each candidate resolve the token at the position being completed -- either a {@code
     * <param>} slot (delegated to {@code commands/tabcomplete/}) or a literal token (collected
     * from every visible sibling format long enough to have one at this position).
     */
    private static List<String> suggestSubsequentToken(BiMap<String, Method> mappings, Player player,
                                                         Command command, String[] args,
                                                         Object executorInstance) {
        List<String> completions = new ArrayList<>();
        int targetIndex = args.length - 1;

        for (Method method : matchCandidates(mappings, player, args, targetIndex)) {
            String format = mappings.inverse().get(method);
            String arg = argAt(format, targetIndex);
            if (isParameterToken(arg)) {
                completions.addAll(suggestParameterSlot(player, command, args, targetIndex, method, arg,
                        executorInstance));
            } else {
                completions.addAll(suggestLiteralSiblingToken(mappings, player, targetIndex));
            }
        }

        return completions;
    }

    private static List<Method> matchCandidates(BiMap<String, Method> mappings, Player player,
                                                  String[] args, int targetIndex) {
        List<Method> candidates = new ArrayList<>();
        for (Map.Entry<String, Method> entry : mappings.entrySet()) {
            Method method = entry.getValue();
            if (!isVisible(player, method)) {
                continue;
            }
            String[] formatArgs = splitFormat(entry.getKey());
            if (formatArgs.length <= targetIndex) {
                continue;
            }
            if (prefixMatches(formatArgs, args, targetIndex)) {
                candidates.add(method);
            }
        }
        return candidates;
    }

    private static boolean prefixMatches(String[] formatArgs, String[] args, int targetIndex) {
        for (int i = 0; i < targetIndex; i++) {
            String formatArg = formatArgs[i];
            if (!isParameterToken(formatArg) && !formatArg.equalsIgnoreCase(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static List<String> suggestParameterSlot(Player player, Command command, String[] args,
                                                       int targetIndex, Method method, String arg,
                                                       Object executorInstance) {
        String parameterName = arg.substring(1, arg.length() - 1);
        TabCompletionContext context = TabCompletionContext.builder()
                .player(player)
                .command(command)
                .args(args)
                .currentArgIndex(targetIndex)
                .partialArg(args[targetIndex])
                .matchedMethod(method)
                .parameterName(parameterName)
                .executorInstance(executorInstance)
                .build();
        // Resolved from @CmdParam.suggest() -- NOT from parameterName (@CmdParam.value(), the
        // display name) -- so a display name starting with "@" is never mistaken for a
        // completer key (05-06 / D-07 Pitfall 2, T-05-28).
        String resolvedSuggest = TabCompletionManager.resolveSuggestValue(method, parameterName);
        return TabCompletionManager.getInstance().suggest(context, resolvedSuggest);
    }

    /**
     * Permission-filtered before it can contribute a suggestion -- T-05-20's mitigation extended
     * to this branch too. The pre-refactor {@code AbstractCommandExecutor.suggest}'s equivalent
     * else-branch scanned every sibling format unconditionally; both generations now share this
     * gated version.
     */
    private static List<String> suggestLiteralSiblingToken(BiMap<String, Method> mappings, Player player,
                                                             int targetIndex) {
        List<String> completions = new ArrayList<>();
        for (Map.Entry<String, Method> entry : mappings.entrySet()) {
            if (!isVisible(player, entry.getValue())) {
                continue;
            }
            String[] formatArgs = splitFormat(entry.getKey());
            if (formatArgs.length <= targetIndex) {
                continue;
            }
            String suggestion = formatArgs[targetIndex];
            if (!isParameterToken(suggestion)) {
                completions.add(suggestion);
            }
        }
        return completions;
    }

    private static String[] splitFormat(String format) {
        return format == null || format.isEmpty() ? new String[0] : format.split(" ");
    }

    private static String argAt(String format, int index) {
        String[] formatArgs = splitFormat(format);
        return index >= 0 && index < formatArgs.length ? formatArgs[index] : "";
    }

    private static boolean isParameterToken(String token) {
        return token != null && token.startsWith("<") && token.endsWith(">");
    }

    private static boolean isVisible(Player player, Method method) {
        return checkPermission(player, method) && checkOp(player, method);
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
