package com.ultikits.ultitools.annotations;

import com.ultikits.ultitools.interfaces.impl.pasers.DefaultConfigParser;
import com.ultikits.ultitools.interfaces.impl.pasers.ConfigParser;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Config entry annotation.
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/config-file.html">Configuration</a>
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigEntry {
    /**
     * @return config entry path
     */
    String path() default "";

    /**
     * @return config entry comment
     */
    String comment() default "";

    /**
     * @return config entry parser
     * @see DefaultConfigParser
     * @deprecated Use ConfigConverter with ConfigConverterFor; removed in the next version.
     * @removeIn 6.4.0
     */
    // Retains the legacy parser attribute until the announced removal version.
    @SuppressWarnings("removal")
    @Deprecated(since = "6.3.0", forRemoval = true)
    Class<? extends ConfigParser> parser() default DefaultConfigParser.class;
}
