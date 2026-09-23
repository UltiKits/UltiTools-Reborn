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
 *
 * @since 6.2.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Scheduled {
    /**
     * Initial delay in ticks before first execution. Default: 0
     *
     * @return delay in ticks
     */
    long delay() default 0;

    /**
     * Repeat interval in ticks. -1 = run once after delay. Default: -1
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
     * {@link #delayKey()} name. Default: {@link AbstractConfigEntity} itself, meaning unbound.
     *
     * @return the bound config entity class
     * @since 6.3.0
     */
    Class<? extends AbstractConfigEntity> config() default AbstractConfigEntity.class;

    /**
     * {@code @ConfigEntry} path whose value, in seconds, is the repeat interval. Default: unbound.
     *
     * @return the bound period key
     * @since 6.3.0
     */
    String periodKey() default "";

    /**
     * {@code @ConfigEntry} path whose value, in seconds, is the initial delay. Default: unbound.
     *
     * @return the bound delay key
     * @since 6.3.0
     */
    String delayKey() default "";
}
