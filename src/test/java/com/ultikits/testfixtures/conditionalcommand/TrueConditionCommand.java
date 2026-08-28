package com.ultikits.testfixtures.conditionalcommand;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.annotations.command.CmdExecutor;

import org.bukkit.command.CommandSender;

/**
 * A command whose {@code @ConditionalOnConfig} condition is deliberately configured to evaluate
 * {@code true} in the paired test's config file -- the control proving the false-condition
 * sibling's absence from Bukkit's command map is due to the gate, not an unrelated scan or
 * registration failure.
 * <br>
 * 一个 {@code @ConditionalOnConfig} 条件在配套测试的配置文件中被特意设为 {@code true} 的命令——
 * 作为对照，证明另一个 false 条件命令从 Bukkit 命令表中缺席是因为门控生效，而不是扫描或注册本身
 * 出了问题。
 */
@CmdExecutor(alias = {"conditionaltruecmd"}, permission = "ultitools.test.conditionaltrue")
@ConditionalOnConfig(value = "config/config.yml", path = "enableTrueCommand")
public class TrueConditionCommand extends BaseCommandExecutor {
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("true-condition command help");
    }
}
