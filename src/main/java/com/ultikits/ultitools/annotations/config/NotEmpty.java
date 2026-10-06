package com.ultikits.ultitools.annotations.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a config value must not be empty. The outcome depends on the value kind:
 * <ul>
 *   <li><b>Text</b> ({@code String}): a value that is null or empty after trimming refuses the module at load,
 *       or the reload, naming the field (D-01).</li>
 *   <li><b>List, set or map</b> (since 6.3.0, #630): a value that a load or reload binds empty - the file holds it
 *       empty or null, or every entry failed to bind - is replaced in memory by the field's declared default, and one
 *       WARNING names the file, the key, the value kind, the value as written and the default. The module loads.</li>
 * </ul>
 * In both cases the config file is never written. The declared default of a list, set or map must itself be non-empty:
 * a {@code @NotEmpty} collection whose declared default is empty refuses the module at load as a declaration error.
 *
 * @see com.ultikits.ultitools.annotations.ConfigEntry
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NotEmpty {
}
