package com.ultikits.ultitools.annotations.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Command parameter annotation.
 * <p>
 * 指令参数注解。
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#quick-start">Command Excutor</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface CmdParam {
    /**
     * @return parameter name <br> 参数名
     */
    String value();

    /**
     * @return parameter suggestion
     * <br>
     * You can pass a method name or a string. UltiTools-API will search for the method in the same class first.
     * If the method is not found, it will search in the class which is indecated in {@link CmdSuggest}.
     * If the method is still not found, it will return the string as the suggestion (i18n supported).
     * <br>
     * 参数补全建议
     * <br>
     * 你可以传入一个方法名或一个字符串。UltiTools-API 会首先在同一个类中寻找这个方法。
     * 如果方法没有找到，它会在 {@link CmdSuggest} 中指定的类中寻找。
     * 如果方法仍然没有找到，它会将字符串作为建议返回（支持国际化）。
     * @see CmdSuggest
     */
    String suggest() default "";
}
