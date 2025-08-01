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
}
