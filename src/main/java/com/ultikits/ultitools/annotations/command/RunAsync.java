package com.ultikits.ultitools.annotations.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @CmdMapping} method body as eligible to run off the main thread.
 * <p>
 * Asynchrony is reserved for pure CPU or I/O work. An async body must not touch world, entity,
 * block or chunk state directly: inject {@link com.ultikits.ultitools.scheduling.Scheduler} and
 * submit global work with {@code scheduler.global().run(...)} or player-owned work with
 * {@code scheduler.entity(player).run(...)}. This annotation does not grant safe Bukkit access.
 * <p>
 * Without this annotation a synchronous command body runs immediately when dispatch already owns
 * the global tick thread; otherwise it is submitted to {@code scheduler.global()}. An async body
 * always uses the framework-owned async scheduler, not the module scheduler. A handler consisting
 * only of tick-thread state access should not carry this annotation.
 * <p>
 * At unload each executor waits up to approximately ten seconds for accepted async bodies, including
 * their validator completion hooks, without interrupting them. Do not wait for a tick-thread handoff
 * inside such a body: the unload thread occupies that thread during the wait, and submissions are
 * rejected once the relevant scheduler owner retires. Several blocked executors can add their waits.
 * <p>
 * Use {@link AsyncCommand} when a processing notice or timeout report is also needed.
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#asynchronous-execution">Asynchronous commands</a>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RunAsync {
}
