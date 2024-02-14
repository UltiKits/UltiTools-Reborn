package com.ultikits.ultitools.annotations.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Run async annotation.
 * <p>
 * 指令异步注解。
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#asynchronous-execution">@RunAsync</a>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RunAsync {
}
