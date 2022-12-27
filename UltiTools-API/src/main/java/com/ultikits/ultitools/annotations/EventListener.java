package com.ultikits.ultitools.annotations;

import java.lang.annotation.*;

/**
 * 事件注释标记
 *
 * @author qianmo
 * @version 1.0.0
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventListener {
    /**
     * @return 归属的功能
     */
    String function() default "";
}
