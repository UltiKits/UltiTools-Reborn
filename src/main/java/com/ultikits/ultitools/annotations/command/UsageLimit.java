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
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#execution-lock">@UsageLimit</a>
 */
@Target(ElementType.METHOD)
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
