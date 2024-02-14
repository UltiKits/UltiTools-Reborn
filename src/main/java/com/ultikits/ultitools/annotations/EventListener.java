package com.ultikits.ultitools.annotations;

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

/**
 * Event listener annotation.
 * <p>
 * 事件监听器注解。
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/event-listener.html">Event Listener</a>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface EventListener {
    boolean manualRegister() default false;
}
