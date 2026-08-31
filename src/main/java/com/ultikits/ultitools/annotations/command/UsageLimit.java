package com.ultikits.ultitools.annotations.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @CmdMapping} method as subject to a concurrency lock, enforced by
 * {@code UsageLockValidator}.
 * <p>
 * {@link LimitType#SENDER} serialises invocations per sender: while one invocation from a given
 * sender is in flight, a second invocation of the SAME mapping from the SAME sender is rejected;
 * a different sender is unaffected. {@link LimitType#ALL} serialises invocations server-wide:
 * while any one invocation is in flight, every OTHER sender's invocation of the same mapping is
 * rejected, regardless of who is invoking it.
 * <p>
 * Acquisition is the gate (GEN-09 / D-02, acquire-as-you-validate): the lock is taken inside
 * validation itself, so a failed acquisition is an ordinary validation rejection -- the mapped
 * method is never invoked. An {@code ALL}-scope lock is released only by the sender that acquired
 * it; no other sender's completion can free it (Pitfall 5 / T-05-03).
 * <p>
 * {@link #ContainConsole()} defaults to {@code true} as of 6.3.0 -- a console sender is subject
 * to this limit unless a mapping opts out explicitly.
 * <p>
 * A mapping -- or the executor class itself -- carrying this annotation with {@link LimitType#SENDER}
 * or {@link LimitType#ALL} whose chain omits {@code UsageLockValidator} is refused at plugin load,
 * naming the offending class and, when known, the offending mapping method (SILENT-11 / D-01,
 * D-04); {@link LimitType#NONE} is exempt since it declares no limit to enforce. No opt-out
 * exists for this refusal: Phase 3 D-08's module-granularity isolation is the accepted escape
 * hatch -- the offending module alone fails to load, every other module still starts.
 * <p>
 * As of 6.3.0 this annotation may also be applied at the class level, for the load-time check
 * above. That is the ONLY thing class-level placement does: {@code UsageLockValidator} itself
 * still reads {@code @UsageLimit} per {@code @CmdMapping} method, not from the declaring class, so
 * a class-level annotation with no annotated mapping method loads successfully (once
 * {@code UsageLockValidator} is present) but locks nothing -- disclosed here rather than silently
 * accepted. Put the annotation on the mapping method itself to actually enforce a lock.
 * <p>
 * 将 {@code @CmdMapping} 方法标记为受并发锁限制，由 {@code UsageLockValidator} 强制执行。
 * <p>
 * {@link LimitType#SENDER} 按发送者串行化调用：当某发送者的一次调用正在进行时，同一发送者对同一映射的
 * 第二次调用会被拒绝；不同发送者互不影响。{@link LimitType#ALL} 在服务器范围内串行化调用：当任意一次调用
 * 正在进行时，任何其他发送者对同一映射的调用都会被拒绝，与调用者身份无关。
 * <p>
 * 获取即为门槛（GEN-09 / D-02，验证即获取）：锁在验证本身内部被获取，因此获取失败即是普通的验证拒绝——
 * 映射方法永远不会被调用。{@code ALL} 范围的锁只能由获取它的发送者释放；任何其他发送者的完成都无法
 * 释放它（Pitfall 5 / T-05-03）。
 * <p>
 * {@link #ContainConsole()} 自 6.3.0 起默认值为 {@code true}——除非映射显式排除，否则控制台发送者也受
 * 此限制约束。
 * <p>
 * 某映射——或执行器类本身——以 {@link LimitType#SENDER} 或 {@link LimitType#ALL} 声明本注解，而其链中
 * 省略了 {@code UsageLockValidator} 时，会在插件加载时被拒绝，并指出问题类，以及（已知时）问题映射方法
 * （SILENT-11 / D-01, D-04）；{@link LimitType#NONE} 不受此约束，因为它本就没有声明任何限制。此拒绝
 * 没有开关：Phase 3 D-08 的模块粒度隔离是被接受的退路——只有问题模块本身加载失败，其余模块正常启动。
 * <p>
 * 自 6.3.0 起本注解也可标注在类上，但那只对上面的加载时检查生效：{@code UsageLockValidator} 本身仍然
 * 只按 {@code @CmdMapping} 方法读取 {@code @UsageLimit}，不会读取声明它的类——因此只标注在类上、没有
 * 任何映射方法携带本注解时，模块会（在 {@code UsageLockValidator} 存在的前提下）正常加载，但不会真正
 * 锁定任何指令。这里如实披露而非静默接受；要真正生效，请把注解标注在映射方法本身上。
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#execution-lock">@UsageLimit</a>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface UsageLimit {
    /**
     * @return the concurrency scope this limit enforces -- see {@link LimitType} <br>
     *         此限制所强制执行的并发范围——参见 {@link LimitType}
     */
    LimitType value();

    /**
     * Whether a console sender is subject to this limit, the same as a player sender.
     * Defaults to {@code true} as of 6.3.0 -- a console sender is included unless a mapping
     * opts out explicitly. <br>
     * 控制台发送者是否与玩家发送者一样受此限制约束。自 6.3.0 起默认值为 {@code true}——除非映射显式排除，
     * 否则控制台默认也受限制。
     *
     * @return whether to contain console <br> 是否包含控制台
     */
    boolean ContainConsole() default true;

    enum LimitType {
        /**
         * No concurrency limit is enforced. <br> 不启用并发限制。
         */
        NONE,
        /**
         * Serialises invocations per sender -- see the class-level javadoc. <br>
         * 按发送者串行化调用——参见类级 javadoc。
         */
        SENDER,
        /**
         * Serialises invocations server-wide -- see the class-level javadoc. <br>
         * 在服务器范围内串行化调用——参见类级 javadoc。
         */
        ALL
    }
}
