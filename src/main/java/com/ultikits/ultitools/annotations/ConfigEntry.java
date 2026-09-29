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
     * The comment written above this entry in the configuration file and shown in the panel.
     * <p>
     * A literal comment is written as it is, one comment line per line break, when the framework adds
     * the entry to the file (a fresh install, or a release that adds the key); an operator's own
     * comment on the entry is then left alone.
     * <p>
     * Since 6.3.0, a comment that is exactly one language key - after trimming, {@code {key}} with a
     * key of ASCII letters, digits, {@code .}, {@code _} and {@code -}, for example {@code comment =
     * "{config.limit}"} - is resolved from the owning module's language catalogue in the server's
     * current language, and written on every framework write of the file, keys already in the file
     * included: the first-boot defaults write, an explicit save, the shutdown save, a panel write, and
     * the first start after an upgrade or a language switch. An operator's hand-written comment on
     * such an entry is replaced; values, literal comments and comments on other entries are not
     * changed. A key the catalogue does not contain is written as the token itself, with one warning
     * naming the module, the file, the entry and the key. A comment that only contains a placeholder
     * inside other text (for example {@code "Message ({player} is the name)"}) is literal.
     *
     * @return the entry's comment: literal text, or one {@code {key}} naming a language catalogue key
     */
    String comment() default "";

    /**
     * @return config entry parser
     * @see DefaultConfigParser
     */
    Class<? extends ConfigParser> parser() default DefaultConfigParser.class;
}
