package com.ultikits.ultitools.annotations;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface EnableAutoRegister {
    String scanPackage() default "";
    boolean eventListener() default true;
    boolean cmdExecutor() default true;
    boolean config() default true;
}
