package com.ultikits.ultitools.annotations;

import java.lang.annotation.*;

/**
 * I18n annotation.
 * <p>
 * I18n注解。
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/i18n.html">I18n</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface I18n {
    /**
     * @return i18n code <br> I18n代码
     * @see <a href="http://www.lingoes.net/en/translator/langcode.htm">I18n Code</a>
     */
    String[] value() default {};
}
