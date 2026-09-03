package com.ultikits.ultitools.annotations.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a String config value matches the specified regular expression.
 * If the value does not match, the module refuses to load naming the field and the pattern - the
 * config file is never rewritten (D-01). The offending value itself is redacted from the refusal
 * message when the field name looks secret-shaped (password/secret/token/credential/apikey).
 *
 * @see com.ultikits.ultitools.annotations.ConfigEntry
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Pattern {
    /** The regular expression pattern to match against. */
    String regex();
}
