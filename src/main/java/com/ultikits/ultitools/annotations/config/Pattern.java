package com.ultikits.ultitools.annotations.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a text config value - a {@code String}, or a {@code char} matched as a one-character string (since
 * 6.3.0, #631) - matches the specified regular expression.
 * If the value does not match, the module refuses to load naming the field and the pattern - the
 * config file is never rewritten (D-01). The offending value itself is redacted from the refusal
 * message when the field name looks secret-shaped (password/secret/token/credential/apikey).
 * <p>
 * On a value type it cannot check, the module is refused at load, before the file is read, naming the field and the
 * annotation (since 6.3.0, #631; maintainer decision of 2026-10-06). So is this annotation on a field that is not a
 * {@code @ConfigEntry} setting, or on a field of a value type reached through a setting (a converter builds that value
 * and the framework never validates its fields - validate them in the converter).
 *
 * @see com.ultikits.ultitools.annotations.ConfigEntry
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Pattern {
    /** The regular expression pattern to match against. */
    String regex();
}
