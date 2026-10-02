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
     * Declares a nested entry path split at every dot. Dots inside keys of a bound map are
     * whole map keys instead; they are not interpreted as annotation path separators.
     * @return the nested config entry path
     */
    String path() default "";

    /**
     * A single trimmed {@code {key}} token resolves through the module catalogue on every
     * load and write. That entry's block comment is framework-owned; literal comments are
     * only supplied for new entries and existing operator comments are retained.
     * @return the literal comment or single catalogue-key token
     */
    String comment() default "";

    /**
     * The default selects the declared-type converter registry. An explicit non-default
     * parser selects the frozen legacy adapter, including its old dotted-key behavior.
     * @return the legacy parser override, or the registry-selecting default
     * @see DefaultConfigParser
     * @deprecated Use ConfigConverter with ConfigConverterFor; removed in the next version.
     * @removeIn 6.4.0
     */
    // Retains the legacy parser attribute until the announced removal version.
    @SuppressWarnings("removal")
    @Deprecated(since = "6.3.0", forRemoval = true)
    Class<? extends ConfigParser> parser() default DefaultConfigParser.class;
}
