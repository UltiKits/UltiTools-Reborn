package com.ultikits.ultitools.annotations.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a config value must not be empty. The outcome depends on the value kind:
 * <ul>
 *   <li><b>Text</b> ({@code String} or {@code char}): a value that is null or empty after trimming refuses the module
 *       at load, or the reload, naming the field (D-01).</li>
 *   <li><b>List, set, map or array</b> (since 6.3.0, #630): a value that a load or reload binds empty - the file holds it
 *       empty or null, or every entry failed to bind - is replaced in memory by the field's declared default, and one
 *       WARNING names the file, the key, the value kind, the value as written and the default. The module loads. A
 *       panel write that would empty the value is refused, like any other constraint violation.</li>
 * </ul>
 * In both cases the config file is never written. The declared default of a list, set, map or array must itself satisfy
 * the field's constraints - be non-empty and inside the field's {@code @Size}, if any: a default that does not refuses
 * the module at load as a declaration error naming the field and the constraint, whatever the file holds.
 * <p>
 * On a value type it cannot check, the module is refused at load, before the file is read, naming the field and the
 * annotation (since 6.3.0, #631; maintainer decision of 2026-10-06). So is this annotation on a field of a config class
 * that is not a {@code @ConfigEntry} setting.
 * <p>
 * It takes effect only on a field that is itself a {@code @ConfigEntry} setting of a config class, judged by that field's
 * declared type. On a field of a value type, a nested class or anything a converter produces it is never checked and not
 * reported (maintainer decision of 2026-10-06): validate such fields in the module's converter.
 *
 * @see com.ultikits.ultitools.annotations.ConfigEntry
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NotEmpty {
}
