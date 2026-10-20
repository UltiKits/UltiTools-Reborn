package com.ultikits.ultitools.annotations;

import java.lang.annotation.*;

/**
 * I18n annotation.
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/i18n.html">I18n</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface I18n {
    /**
     * @return i18n code
     * @see <a href="http://www.lingoes.net/en/translator/langcode.htm">I18n Code</a>
     */
    String[] value() default {};
}
