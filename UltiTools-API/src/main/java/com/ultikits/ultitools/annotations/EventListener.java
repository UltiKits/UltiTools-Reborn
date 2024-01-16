package com.ultikits.ultitools.annotations;

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface EventListener {
    boolean manualRegister() default false;
}
