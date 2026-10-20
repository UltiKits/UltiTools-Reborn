package com.ultikits.ultitools.annotations.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Command suggest annotation.
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#cmdsuggest">@CmdSuggest</a>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface CmdSuggest {
    /**
     * @return suggest class <br> {@link CmdParam#suggest()} suggest method will be searched in this class.
     */
    Class<?>[] value();
}
