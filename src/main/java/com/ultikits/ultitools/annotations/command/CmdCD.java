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
 * supplies one. A custom chain that omits {@code CooldownValidator} while a mapping -- or the
 * executor class itself -- declares this annotation is refused at plugin load, naming the
 * offending class and, when known, the offending mapping method (SILENT-11 / D-01, D-04). No
 * opt-out exists for this refusal: Phase 3 D-08's module-granularity isolation is the accepted
 * escape hatch -- the offending module alone fails to load, every other module still starts.
 * <p>
 * As of 6.3.0 this annotation may also be applied at the class level, for the load-time check
 * above. That is the ONLY thing class-level placement does: {@code CooldownValidator} itself
 * still reads {@code @CmdCD} per {@code @CmdMapping} method, not from the declaring class, so a
 * class-level annotation with no annotated mapping method loads successfully (once
 * {@code CooldownValidator} is present) but cools down nothing -- disclosed here rather than
 * silently accepted. Put the annotation on the mapping method itself to actually enforce a
 * cooldown.
 * <p>
 * 将 {@code @CmdMapping} 方法标记为受每玩家冷却限制。
 * <p>
 * 生效要求执行器的 {@link com.ultikits.ultitools.abstracts.command.validation.ValidatorChain} 中包含
 * {@code CooldownValidator}。由
 * {@link com.ultikits.ultitools.abstracts.command.BaseCommandExecutor#createDefaultValidatorChain()}
 * 构建的默认链已包含一个。若自定义链在某映射——或执行器类本身——声明本注解的同时省略了
 * {@code CooldownValidator}，会在插件加载时被拒绝，并指出问题类，以及（已知时）问题映射方法
 * （SILENT-11 / D-01, D-04）。此拒绝没有开关：Phase 3 D-08 的模块粒度隔离是被接受的退路——
 * 只有问题模块本身加载失败，其余模块正常启动。
 * <p>
 * 自 6.3.0 起本注解也可标注在类上，但那只对上面的加载时检查生效：{@code CooldownValidator}
 * 本身仍然只按 {@code @CmdMapping} 方法读取 {@code @CmdCD}，不会读取声明它的类——因此只标注在类上、
 * 没有任何映射方法携带本注解时，模块会（在 {@code CooldownValidator} 存在的前提下）正常加载，但
 * 不会真正冷却任何指令。这里如实披露而非静默接受；要真正生效，请把注解标注在映射方法本身上。
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#command-cooldown">Command cooldown</a>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface CmdCD {
    /**
     * @return cooldown time in seconds; a value of 0 or less disables the cooldown for this
     *         mapping <br> 冷却时间（秒）；小于等于 0 表示该映射不启用冷却
     */
    int value() default 0;
}
