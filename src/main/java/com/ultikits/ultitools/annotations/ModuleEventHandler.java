package com.ultikits.ultitools.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.ultikits.ultitools.events.EventPriority;

/**
 * Marks a method as a module event handler.
 * The method must have exactly one parameter extending ModuleEvent.
 * <p>
 * 标记一个方法为模块事件处理器。
 * 该方法必须有且仅有一个 ModuleEvent 子类参数。
 *
 * @since 6.2.2
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ModuleEventHandler {
    EventPriority priority() default EventPriority.NORMAL;
    boolean ignoreCancelled() default false;
}
