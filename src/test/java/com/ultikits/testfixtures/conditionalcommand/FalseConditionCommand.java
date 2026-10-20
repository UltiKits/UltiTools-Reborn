package com.ultikits.testfixtures.conditionalcommand;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.annotations.command.CmdExecutor;

import org.bukkit.command.CommandSender;

/**
 * A command whose {@code @ConditionalOnConfig} condition is deliberately configured to evaluate
 * {@code false} in the paired test's config file. It must never become a bean, and its alias
 * must never appear in Bukkit's command map.
 * <br>
 * 一个 {@code @ConditionalOnConfig} 条件在配套测试的配置文件中被特意设为 {@code false} 的命令。
 * 它绝不能成为 bean，其别名也绝不能出现在 Bukkit 的命令表中。
 */
@CmdExecutor(alias = {"conditionalfalsecmd"}, permission = "ultitools.test.conditionalfalse")
@ConditionalOnConfig(value = "config/config.yml", path = "enableFalseCommand")
public class FalseConditionCommand extends BaseCommandExecutor {
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("false-condition command help");
    }
}
