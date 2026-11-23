package com.ultikits.ultitools.text;

import java.time.temporal.TemporalAccessor;
import java.util.Objects;
import java.util.regex.Pattern;
import net.kyori.adventure.text.ComponentLike;

/**
 * A named message argument, referenced by a translation as {@code <player>}.
 * Values are formatted at render time in the target locale; positional arguments are unsupported.
 * The set of permitted subtypes may grow in a 7.x minor release. Code outside the framework
 * must not switch over it exhaustively without a {@code default} branch.
 */
public sealed interface Arg permits Arg.Unparsed, Arg.Numeric, Arg.Moment, Arg.Localized {
    /** Returns the tag name, matching {@code [a-z0-9_-]+}. */
    String name();

    /** Returns a literal string argument; MiniMessage tags in its value are never interpreted. */
    static Arg string(String name, String value) {
        return new Unparsed(name, value);
    }

    /** Returns a number formatted with the target locale unless the translation specifies a pattern. */
    static Arg number(String name, Number value) {
        return new Numeric(name, value);
    }

    /** Returns a date/time formatted with the target locale unless the translation specifies a pattern. */
    static Arg time(String name, TemporalAccessor value) {
        return new Moment(name, value);
    }

    /** Returns deferred text, rendered in the enclosing message's locale. */
    static Arg text(String name, Text value) {
        return new Localized(name, value);
    }

    /** Shorthand for {@code text(name, Text.literal(value))}. */
    static Arg component(String name, ComponentLike value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        return text(name, Text.literal(value));
    }

    private static void validateName(String name) {
        Objects.requireNonNull(name, "name");
        if (!Pattern.matches("[a-z0-9_-]+", name)) {
            throw new IllegalArgumentException("Invalid argument name: " + name);
        }
    }

    /** A plain-text argument. @param name tag name @param value unparsed characters */
    record Unparsed(String name, String value) implements Arg {
        /** Validates the name and rejects a null value. */
        public Unparsed {
            validateName(name);
            Objects.requireNonNull(value, "value");
        }
    }

    /** A number argument. @param name tag name @param value number to format */
    record Numeric(String name, Number value) implements Arg {
        /** Validates the name and rejects a null value. */
        public Numeric {
            validateName(name);
            Objects.requireNonNull(value, "value");
        }
    }

    /** A date/time argument. @param name tag name @param value temporal value to format */
    record Moment(String name, TemporalAccessor value) implements Arg {
        /** Validates the name and rejects a null value. */
        public Moment {
            validateName(name);
            Objects.requireNonNull(value, "value");
        }
    }

    /** Deferred localized text. @param name tag name @param value text to render */
    record Localized(String name, Text value) implements Arg {
        /** Validates the name and rejects a null value. */
        public Localized {
            validateName(name);
            Objects.requireNonNull(value, "value");
        }
    }
}
