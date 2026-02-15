package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for automatic scheduled execution by the framework.
 * <p>
 * 标记一个方法由框架自动调度执行。
 * <p>
 * The annotated method must be {@code void} and take no parameters.
 * It must be inside a {@code @Service} or other managed bean.
 * Tasks are automatically cancelled when the owning plugin is unloaded.
 * <p>
 * 被注解的方法必须是 {@code void} 且无参数。
 * 必须在 {@code @Service} 或其他托管 Bean 中使用。
 * 当所属插件卸载时，任务会自动取消。
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
     * <p>
     * 首次执行前的延迟tick数。默认：0
     *
     * @return delay in ticks
     */
    long delay() default 0;

    /**
     * Repeat interval in ticks. -1 = run once after delay. Default: -1
     * <p>
     * 重复间隔tick数。-1 = 延迟后只执行一次。默认：-1
     *
     * @return period in ticks
     */
    long period() default -1;

    /**
     * Run on async thread instead of main server thread. Default: false
     * <p>
     * 在异步线程而非主线程上运行。默认：false
     *
     * @return true if async
     */
    boolean async() default false;
}
