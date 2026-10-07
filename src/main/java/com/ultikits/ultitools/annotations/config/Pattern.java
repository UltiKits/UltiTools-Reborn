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
 * annotation (since 6.3.0, #631; maintainer decision of 2026-10-06). So is this annotation on a field of a config class
 * that is not a {@code @ConfigEntry} setting.
 * <p>
 * It takes effect only on a field that is itself a {@code @ConfigEntry} setting of a config class, judged by that field's
 * declared type. A declared type that can hold a value the annotation checks - {@code Object}, {@code Serializable},
 * {@code Comparable}, {@code CharSequence}, {@code Number} or another supertype of a checked kind - is accepted, and the
 * value bound at load is checked; only a type that can never hold a checkable value is a declaration error. A bound
 * value of a kind the annotation cannot read (text under {@code @Range}, a number under {@code @Pattern}) is a violation
 * like any other: the module is refused at load, and a reload is refused with the running values kept. On a field of a value type, a nested class or anything a converter produces it is never checked and not
 * reported (maintainer decision of 2026-10-06): validate such fields in the module's converter.
 *
 * @see com.ultikits.ultitools.annotations.ConfigEntry
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Pattern {
    /** The regular expression pattern to match against. */
    String regex();
}
