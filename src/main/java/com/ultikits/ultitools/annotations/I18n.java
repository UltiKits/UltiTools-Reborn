package com.ultikits.ultitools.annotations;

import java.lang.annotation.*;

/**
 * Legacy language declaration, retained for source and binary compatibility.
 *
 * @deprecated Deprecated since 6.5.0. Since 6.3.0, languages come from the {@code lang/*} files the module JAR ships
 * ({@code .json}, {@code .yml}, or {@code .yaml}), not this declaration. Remove the annotation
 * and ship the language files instead. This annotation may be removed as early as 6.6.0.
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/i18n.html">I18n</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
@Deprecated(since = "6.5.0", forRemoval = true)
public @interface I18n {
    /**
     * @return i18n code
     * @see <a href="http://www.lingoes.net/en/translator/langcode.htm">I18n Code</a>
     */
    String[] value() default {};
}
