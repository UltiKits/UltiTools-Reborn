package com.ultikits.ultitools.annotations.command;

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
 * As of 6.3.0 this annotation may also be applied at the class level, for the load-time check
 * above. That is the ONLY thing class-level placement does: {@code CooldownValidator} itself
 * still reads {@code @CmdCD} per {@code @CmdMapping} method, not from the declaring class, so a
 * class-level annotation with no annotated mapping method loads successfully (once
 * {@code CooldownValidator} is present) but cools down nothing -- disclosed here rather than
 * silently accepted. Put the annotation on the mapping method itself to actually enforce a
 * cooldown.
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#command-cooldown">Command cooldown</a>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface CmdCD {
    /**
     * @return cooldown time in seconds; a value of 0 or less disables the cooldown for this
     *         mapping
     */
    int value() default 0;
}
