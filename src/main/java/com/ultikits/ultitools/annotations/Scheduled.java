package com.ultikits.ultitools.annotations;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for automatic scheduled execution by the framework.
 * <p>
 * The annotated method must be {@code void} and take no parameters.
 * It must be inside a {@code @Service} or other managed bean.
 * Tasks are automatically cancelled when the owning plugin is unloaded.
 * {@link com.ultikits.ultitools.manager.TaskManager} walks the class hierarchy when scanning for
 * {@code @Scheduled} methods, so an annotated method is still found on a ByteBuddy AOP proxy of
 * the declaring bean.
 *
 * <p>Usage example:
 * <pre>{@code
 * @Service
 * public class InterestService {
 *     @Scheduled(period = 36000, async = true)  // Every 30 minutes, async
 *     public void distributeInterest() {
 *         // Framework calls this automatically
 *     }
 * }
 * }</pre>
 * {@code async = true} is for literal timings only: a {@code @Scheduled} bound to a config key
 * (below) must be sync, and {@code async = true} together with a binding refuses the module at load
 * (#535 tracks async bindings).
 *
 * <h2>Binding the timing to a config key (6.3.0)</h2>
 * Instead of a literal, {@link #periodKey()} and {@link #delayKey()} can name a
 * {@code @ConfigEntry} path on the module's own config entity, given by {@link #config()}. The
 * value is read in <b>seconds</b> and multiplied by 20 for ticks, so an operator can tune the
 * interval without a second, hand-written scheduler in the module:
 * <pre>{@code
 * @Scheduled(config = EconomyConfig.class, periodKey = "interest.interval", delayKey = "interest.interval")
 * public void distributeInterest() { ... }
 * }</pre>
 * <ul>
 *   <li><b>The default lives only in the config field</b> ({@code private int interestInterval = 1800;}).
 *       Leave the literal it replaces unset: {@code period} with {@code periodKey}, {@code delay}
 *       with {@code delayKey}. Setting both refuses the module at load.</li>
 *   <li><b>Checked at load.</b> The module alone is refused, naming the key and the value, when the
 *       config class is not registered exactly once for the module (a directory
 *       {@code @ConfigEntity} cannot be bound), the key matches no {@code @ConfigEntry} path, the
 *       field is not an {@code int}, {@code long}, {@code Integer} or {@code Long}, or the value is
 *       below 1 second, {@code null} or above {@code Integer.MAX_VALUE / 20} seconds (about 3.4
 *       years). {@code 0} does not mean "off".</li>
 *   <li><b>Declared methods only.</b> The binding is found on the bean class's own declared
 *       methods (after unwrapping an AOP proxy), exactly like an unbound {@code @Scheduled}; a
 *       method inherited from a superclass is neither scheduled nor checked. Declare the bound
 *       method on the bean class itself (see issue #532).</li>
 *   <li><b>Applied at {@code /ul reload}</b>, keeping the task's place in its cycle: the next run
 *       is the last run plus the new period (before the first run: the arm time plus the new
 *       delay), or the next tick if that moment has already passed. A reload never runs the task
 *       early and never postpones it by restarting its clock; a task whose value did not change is
 *       not touched. An invalid value on reload is not applied -- the running value is kept and a
 *       WARNING names the key. A panel edit takes effect at the next {@code /ul reload}. The
 *       reload step runs on the main thread only.</li>
 *   <li><b>Panel edits are range-checked.</b> A panel write that sets a bound key outside this
 *       range (for example {@code 0}) is refused like a {@code @Range} violation, and nothing is
 *       written.</li>
 *   <li><b>No {@code @Range} on a bound field.</b> The binding's range above is the field's range.
 *       A module {@code @Range} on the same field would make an out-of-range reload throw from the
 *       config reload itself, which aborts the rest of that module's reload (issue #509) instead of
 *       keeping the running value.</li>
 *   <li><b>Sync only.</b> A bound method cannot be {@code async = true}; that combination is
 *       refused at load. Bind a sync task and hand the heavy work to
 *       {@code Bukkit.getScheduler().runTaskAsynchronously(...)} from its body. A sync task's runs
 *       happen on the main thread, where the reload also runs, which is what lets a reload keep
 *       the task's place in its cycle exactly. Literal (unbound) {@code async} tasks are
 *       unaffected. Issue #535 tracks allowing async bindings.</li>
 *   <li><b>Modules only.</b> A binding on an External Plugin API bean is refused.</li>
 *   <li><b>Declare {@code api-version: 630}</b> in the module's {@code plugin.yml}. An older
 *       framework does not know these elements and silently ignores them -- the method would then
 *       run once at load instead of on the configured interval -- so the floor is what makes it
 *       refuse the module instead. 6.3.0 itself refuses a module that uses a binding while
 *       declaring a lower {@code api-version}, so the mistake shows up where it is made.</li>
 * </ul>
 * A method with no binding behaves exactly as before 6.3.0.
 *
 * @since 6.2.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Scheduled {
    /**
     * Initial delay in ticks before first execution. Default: 0. Leave unset when
     * {@link #delayKey()} is set.
     *
     * @return delay in ticks
     */
    long delay() default 0;

    /**
     * Repeat interval in ticks. -1 = run once after delay. Default: -1. Leave unset when
     * {@link #periodKey()} is set.
     *
     * @return period in ticks
     */
    long period() default -1;

    /**
     * Run on async thread instead of main server thread. Default: false
     *
     * @return true if async
     */
    boolean async() default false;

    /**
     * Config entity class whose {@code @ConfigEntry} keys {@link #periodKey()} and
     * {@link #delayKey()} name. It must be registered exactly once for the module, and the value
     * is read from that same instance, which {@code /ul reload} reloads in place. Default:
     * {@link AbstractConfigEntity} itself, meaning unbound. See "Binding the timing to a config
     * key" above.
     *
     * @return the bound config entity class
     * @since 6.3.0
     */
    Class<? extends AbstractConfigEntity> config() default AbstractConfigEntity.class;

    /**
     * {@code @ConfigEntry} path whose value, in seconds (at least 1), is the repeat interval. The
     * path is matched as {@code @ConfigEntry(path = ...)} declares it, or the field name when the
     * path is empty. Requires {@link #config()}; excludes {@link #period()}. Default: unbound.
     *
     * @return the bound period key
     * @since 6.3.0
     */
    String periodKey() default "";

    /**
     * {@code @ConfigEntry} path whose value, in seconds (at least 1), is the initial delay. May
     * name the same key as {@link #periodKey()} -- a task that must not run at load, such as a
     * payout, waits one full interval first. Requires {@link #config()}; excludes {@link #delay()}.
     * Default: unbound.
     *
     * @return the bound delay key
     * @since 6.3.0
     */
    String delayKey() default "";
}
