package com.ultikits.ultitools.annotations.command;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @CmdMapping} method as subject to a per-player cooldown.
 * <p>
 * Enforcement requires a {@code CooldownValidator} in the executor's
 * {@link com.ultikits.ultitools.abstracts.command.validation.ValidatorChain}. The default chain
 * built by {@link com.ultikits.ultitools.abstracts.command.BaseCommandExecutor#createDefaultValidatorChain()}
 * supplies one. A custom chain that omits {@code CooldownValidator} while a mapping -- or the
 * executor class itself -- declares this annotation is refused at plugin load, naming the
 * offending class and, when known, the offending mapping method (SILENT-11 / D-01, D-04). No
 * opt-out exists for this refusal: Phase 3 D-08's module-granularity isolation is the accepted
 * escape hatch -- the offending module alone fails to load, every other module still starts.
 * <p>
 * As of 6.3.0 this annotation may also be applied at the class level. {@code CooldownValidator}
 * resolves it most-derived-wins: a mapping method's own {@code @CmdCD} first, then a class-level
 * one on the concrete executor class, then one on the method's declaring class. A class-level
 * annotation therefore cools down every mapping that does not declare its own.
 *
 * <h2>Binding the cooldown to a config key (6.3.0)</h2>
 * Instead of {@link #value()}, {@link #key()} can name a {@code @ConfigEntry} path on the module's
 * own config entity, given by {@link #config()}; the value is in seconds, like {@link #value()}:
 * <pre>{@code
 * @CmdCD(config = EssentialsConfig.class, key = "features.wild.cooldown")
 * }</pre>
 * <ul>
 *   <li><b>The default lives only in the config field.</b> Leave {@link #value()} unset; setting
 *       both refuses the module at load.</li>
 *   <li><b>Checked at load</b>, refusing the module alone and naming the key and the value, when
 *       the config class is not registered exactly once for the module, the key matches no
 *       {@code @ConfigEntry} path, the field is not an {@code int}, {@code long}, {@code Integer}
 *       or {@code Long}, or the value is negative, {@code null} or above
 *       {@link Integer#MAX_VALUE}. A bound value of {@code 0} means "no cooldown", the same as
 *       {@code value = 0}.</li>
 *   <li><b>Applied at {@code /ul reload}.</b> The resolved seconds are cached per executor and
 *       refreshed only after a successful configuration reload, so a panel edit takes effect at
 *       the next {@code /ul reload} and a refused reload never takes effect. An invalid value on
 *       reload keeps the running one and logs a WARNING. A cooldown already running keeps the end
 *       time it was stamped with, whatever the new value is: a reload to {@code 0} stamps no new
 *       cooldown, and the running ones expire on their own. Executors sharing one validator chain
 *       keep all their bindings.</li>
 *   <li><b>Panel edits are range-checked.</b> A panel write that sets a bound key outside this
 *       range (a negative value, for example) is refused like a {@code @Range} violation, and
 *       nothing is written.</li>
 *   <li><b>No {@code @Range} on a bound field.</b> The binding's range above is the field's range.
 *       A module {@code @Range} on the same field would make an out-of-range reload throw from the
 *       config reload itself, which aborts the rest of that module's reload (issue #509) instead
 *       of keeping the running value.</li>
 *   <li><b>Modules only.</b> A binding in an External Plugin API executor is refused.</li>
 *   <li><b>Declare {@code api-version: 630}</b> in the module's {@code plugin.yml}: an older
 *       framework silently ignores these elements and would enforce no cooldown at all. 6.3.0
 *       refuses a module that uses a binding while declaring a lower {@code api-version}.</li>
 * </ul>
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#command-cooldown">Command cooldown</a>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface CmdCD {
    /**
     * @return cooldown time in seconds; a value of 0 or less disables the cooldown for this
     *         mapping. Leave unset when {@link #key()} is set.
     */
    int value() default 0;

    /**
     * Config entity class whose {@code @ConfigEntry} key {@link #key()} names; it must be
     * registered exactly once for the module. Default: {@link AbstractConfigEntity} itself,
     * meaning unbound. See "Binding the cooldown to a config key" above.
     *
     * @return the bound config entity class
     * @since 6.3.0
     */
    Class<? extends AbstractConfigEntity> config() default AbstractConfigEntity.class;

    /**
     * {@code @ConfigEntry} path whose value, in seconds (0 for no cooldown), is the cooldown. Matched as
     * {@code @ConfigEntry(path = ...)} declares it, or the field name when the path is empty.
     * Requires {@link #config()}; excludes {@link #value()}. Default: unbound.
     *
     * @return the bound cooldown key
     * @since 6.3.0
     */
    String key() default "";
}
