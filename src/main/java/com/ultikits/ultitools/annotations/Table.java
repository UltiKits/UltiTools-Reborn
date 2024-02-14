package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Table annotation.
 * <p>
 * 持久化数据表注释
 *
 * @author wisdomme
 * @version 1.0.0
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/data-storage.html#table">@Table</a>
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Table {
    /**
     * @return Data table/file name <br> 数据表/文件夹名称
     */
    String value();
}
