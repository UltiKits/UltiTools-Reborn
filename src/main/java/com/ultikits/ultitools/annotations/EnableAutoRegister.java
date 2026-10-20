package com.ultikits.ultitools.annotations;

import java.lang.annotation.*;

/**
 * Enable auto register annotation.
 *
 * @see <a href="https://dev.ultikits.com/en/guide/advanced/auto-register.html">Auto Register</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface EnableAutoRegister {
    /**
     * @return scan package
     */
    String scanPackage() default "";

    /**
     * @return whether auto register event listener
     */
    boolean eventListener() default true;

    /**
     * @return whether auto register command executor
     */
    boolean cmdExecutor() default true;

    /**
     * @return whether auto register config entity
     */
    boolean config() default true;
}
