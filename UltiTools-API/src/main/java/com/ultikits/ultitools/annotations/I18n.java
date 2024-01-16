package com.ultikits.ultitools.annotations;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface I18n {
    String[] value() default {};
}
