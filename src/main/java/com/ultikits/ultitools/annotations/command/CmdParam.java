package com.ultikits.ultitools.annotations.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Command parameter annotation.
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#quick-start">Command Excutor</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface CmdParam {
    /**
     * @return parameter name
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
     *
     * @see CmdSuggest
     * @see com.ultikits.ultitools.commands.tabcomplete.TabCompletionManager
     */
    String suggest() default "";
}
