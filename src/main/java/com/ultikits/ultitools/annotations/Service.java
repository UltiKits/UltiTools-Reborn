package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Service annotation to replace Spring's @Service.
 * <br>
 * 服务注解，用于替换Spring的@Service。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface Service {
    /**
     * Service name.
     * <br>
     * 服务名称。
     *
     * @return service name <br> 服务名称
     */
    String value() default "";
    
    /**
     * Service priority. Higher value means higher priority.
     * When multiple implementations of the same interface exist,
     * the one with highest priority will be returned by getBean(Class).
     * <br>
     * 服务优先级。数值越大优先级越高。
     * 当同一接口存在多个实现时，getBean(Class)会返回优先级最高的实现。
     *
     * @return priority value (default 0) <br> 优先级值（默认0）
     */
    int priority() default 0;
}
