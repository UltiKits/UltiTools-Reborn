package com.ultikits.ultitools.annotations.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @CmdMapping} method as subject to a per-player cooldown.
 * <p>
 * Enforcement requires a {@code CooldownValidator} in the executor's
 * {@link com.ultikits.ultitools.abstracts.command.validation.ValidatorChain}. The default chain
 * built by {@link com.ultikits.ultitools.abstracts.command.BaseCommandExecutor#createDefaultValidatorChain()}
 * supplies one. A custom chain that omits {@code CooldownValidator} while a mapping declares this
 * annotation must be refused at plugin load, naming the offending class -- not silently
 * unenforced (SILENT-11 / D-01, D-04). As of this plan, that load-time refusal is not yet
 * implemented; this javadoc states the contract the framework is committed to, and a later phase
 * plan enforces it.
 * <p>
 * 将 {@code @CmdMapping} 方法标记为受每玩家冷却限制。
 * <p>
 * 生效要求执行器的 {@link com.ultikits.ultitools.abstracts.command.validation.ValidatorChain} 中包含
 * {@code CooldownValidator}。由
 * {@link com.ultikits.ultitools.abstracts.command.BaseCommandExecutor#createDefaultValidatorChain()}
 * 构建的默认链已包含一个。若自定义链在某映射声明本注解的同时省略了 {@code CooldownValidator}，必须在
 * 插件加载时被拒绝并指出问题类，而不是静默地不生效（SILENT-11 / D-01, D-04）。截至本计划，该加载时拒绝
 * 机制尚未实现；本 javadoc 陈述框架所承诺的契约，由后续阶段计划实现。
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#command-cooldown">Command cooldown</a>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CmdCD {
    /**
     * @return cooldown time in seconds; a value of 0 or less disables the cooldown for this
     *         mapping <br> 冷却时间（秒）；小于等于 0 表示该映射不启用冷却
     */
    int value() default 0;
}
