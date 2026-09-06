package com.ultikits.ultitools.annotations.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @CmdMapping} method body as eligible to run off the main thread -- see below
 * for when that is, and is not, actually safe.
 * <p>
 * <b>An {@code @RunAsync} body must not touch world, entity, block or chunk state
 * directly.</b>
 * Asynchrony here is reserved for pure-CPU or I/O work. The one Bukkit call an async body
 * may make is scheduling its state-touching work back onto the main thread through
 * {@link org.bukkit.scheduler.BukkitScheduler#runTask(org.bukkit.plugin.Plugin, Runnable)};
 * this annotation never grants safe access to those APIs by itself. A handler whose body
 * consists only of such state access has no reason to carry the annotation at all.
 * <p>
 * Without this annotation, a synchronous command body is not run inline on the calling thread --
 * the framework already defers it by one tick via {@code runTask()} in
 * {@link com.ultikits.ultitools.abstracts.command.BaseCommandExecutor}. Removing
 * {@code @RunAsync} from a handler that never needed it is therefore not "making the command
 * block the server"; it restores the same one-tick-deferred synchronous dispatch every other
 * command already uses.
 * <p>
 * The observable failure when this rule is broken is not a slow command -- it is a crash.
 * Bukkit's async-scheduler dispatch (see {@code runTaskAsynchronously()} in
 * {@link com.ultikits.ultitools.abstracts.command.BaseCommandExecutor}) runs the body off the main thread unconditionally, and the first
 * call into world, entity, block or chunk state trips Paper's asynchronous-operation check and
 * kills the command mid-handler.
 * <p>
 * For work that genuinely belongs off-thread -- and needs a processing message, a timeout, or
 * configurable behavior around that boundary -- use {@link AsyncCommand} instead of this
 * annotation.
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#asynchronous-execution">@RunAsync</a>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RunAsync {
}
