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
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#execution-lock">@UsageLimit</a>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface UsageLimit {
    /**
     * @return the concurrency scope this limit enforces -- see {@link LimitType}
     */
    LimitType value();

    /**
     * Whether a console sender is subject to this limit, the same as a player sender.
     * Defaults to {@code true} as of 6.3.0 -- a console sender is included unless a mapping
     * opts out explicitly.
     *
     * @return whether to contain console
     */
    boolean ContainConsole() default true;

    enum LimitType {
        /**
         * No concurrency limit is enforced.
         */
        NONE,
        /**
         * Serialises invocations per sender -- see the class-level javadoc.
         */
        SENDER,
        /**
         * Serialises invocations server-wide -- see the class-level javadoc.
         */
        ALL
    }
}
