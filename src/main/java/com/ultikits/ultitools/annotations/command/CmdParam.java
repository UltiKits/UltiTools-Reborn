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
     * Resolution proceeds in up to four steps, in order (05-06 / D-07):
     * <ol>
     *     <li>A value starting with {@code @} (e.g. {@code "@players"}) resolves through a registered
     *     {@link com.ultikits.ultitools.commands.tabcomplete.TabCompleter} -- either one of the framework's
     *     four built-in keys ({@code @players}, {@code @worlds}, {@code @materials} (plus
     *     {@code @blocks}/{@code @items}), {@code @boolean} (plus {@code @toggle}) -- see
     *     {@link com.ultikits.ultitools.commands.tabcomplete.TabCompletionManager}) or a key a module
     *     registered at runtime. An <b>unknown</b> {@code @key} refuses the declaring module to load,
     *     naming the class, the method and the key -- it does NOT fall through to step 4.</li>
     *     <li>Any other value is treated as a method name. UltiTools-API will search for that method in
     *     the same class first.</li>
     *     <li>If the method is not found, it will search in the class which is indecated in
     *     {@link CmdSuggest}.</li>
     *     <li>If the method is still not found, it will return the string as the suggestion (i18n
     *     supported).</li>
     * </ol>
     * {@code @} is not a legal Java identifier start, so a plain method name can never collide with the
     * {@code @key} notation -- every pre-existing {@code suggest()} site needs zero change.
     * <br>
     * 参数补全建议
     * <br>
     * 解析按顺序最多分四步进行（05-06 / D-07）：
     * <ol>
     *     <li>以 {@code @} 开头的值（例如 {@code "@players"}）会通过一个已注册的
     *     {@link com.ultikits.ultitools.commands.tabcomplete.TabCompleter} 解析——可以是框架内置
     *     的四个键之一，也可以是模块在运行时注册的键。未知的 {@code @key} 会拒绝声明它的
     *     模块加载，并在信息中指明类、方法和这个键——它不会退回到第四步。</li>
     *     <li>其他任何值都被当作方法名。UltiTools-API 会首先在同一个类中寻找这个方法。</li>
     *     <li>如果没有找到，它会在 {@link CmdSuggest} 中指定的类中寻找。</li>
     *     <li>如果方法仍然没有找到，它会将字符串作为建议返回（支持国际化）。</li>
     * </ol>
     * {@code @} 不是合法的 Java 标识符起始字符，因此普通方法名永远不会与 {@code @key} 表示法冲突。
     * @see CmdSuggest
     * @see com.ultikits.ultitools.commands.tabcomplete.TabCompletionManager
     */
    String suggest() default "";
}
