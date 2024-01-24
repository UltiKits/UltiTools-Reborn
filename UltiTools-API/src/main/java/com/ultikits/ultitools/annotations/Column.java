package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Column annotation.
 * <p>
 * 数据集列注解。
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/data-storage.html#column">@Column</a>
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Column {
    String value();

    String type() default "VARCHAR(255)";
}
