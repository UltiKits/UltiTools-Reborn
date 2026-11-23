package com.ultikits.ultitools.text;

import java.time.temporal.TemporalAccessor;
import java.util.Objects;
import java.util.regex.Pattern;
import net.kyori.adventure.text.ComponentLike;

/**
 * A named message argument. A translation is a MiniMessage string that refers to arguments by name:
 * {@code "Welcome, <player>!"}. Positional arguments are not supported. An argument name takes
 * precedence over a built-in MiniMessage tag of the same name ({@code <newline>}, {@code <score>}, ...).
 *
 * <p>Formatting is chosen by the translation, in the render locale:
 * <ul>
 *   <li>{@link #number}: {@code <n>} uses the locale's default number format;
 *       {@code <n:'#,##0.00'>} is a {@link java.text.DecimalFormat} pattern with the locale's symbols;
 *       {@code <n:'de-DE':'#,##0.00'>} names the locale explicitly (Adventure's two-argument form);
 *       {@code <n:choice:'0#no items|1#one item|1<<n> items'>} selects text with a
 *       {@link java.text.ChoiceFormat} pattern, and the selected text is parsed as MiniMessage with the
 *       same arguments, so {@code <n>} inside it prints the formatted number.</li>
 *   <li>{@link #time}: {@code <t>} uses the locale's MEDIUM style for the fields the value has
 *       (date and time, date only, or time only); {@code <t:'d MMMM yyyy'>} is a
 *       {@link java.time.format.DateTimeFormatter} pattern applied with the render locale.
 *       An {@link java.time.Instant} is shown in the server's default zone
 *       ({@link java.time.ZoneId#systemDefault()} at render time).</li>
 *   <li>{@link #string} and {@link #text} take no format argument.</li>
 * </ul>
 * Quote a pattern that contains {@code :}, {@code <}, {@code >} or a quote; quoting is always allowed.
 * There is no boolean choice and no CLDR plural category in 7.0; a later minor may add argument kinds.
 *
 * <p>A format the engine cannot apply (a malformed pattern, an unknown keyword, a field the value does
 * not have) never makes rendering throw: the value is shown in its kind's default format for the
 * render locale (or {@code String.valueOf(value)} if even that fails), and one WARNING is logged per
 * (message key, locale, argument name).
 *
 * <p>The set of permitted subtypes may grow in a 7.x minor release. Code outside the framework must
 * not switch over it exhaustively without a {@code default} branch.
 */
public sealed interface Arg permits Arg.Unparsed, Arg.Numeric, Arg.Moment, Arg.Localized {
    /** Returns the tag name, matching {@code [a-z0-9_-]+}. */
    String name();

    /** Returns a literal string argument; MiniMessage tags in its value are never interpreted. */
    static Arg string(String name, String value) {
        return new Unparsed(name, value);
    }

    /**
     * Returns a number formatted with the target locale unless the translation specifies a pattern.
     * The value must be immutable (use Integer, Long, Double, BigDecimal ...); a mutable Number such as
     * AtomicInteger makes equal texts unequal later and changes what renders.
     */
    static Arg number(String name, Number value) {
        return new Numeric(name, value);
    }

    /**
     * A date/time argument. Accepted values: {@link java.time.Instant} (shown in
     * {@link java.time.ZoneId#systemDefault()} at render time), {@link java.time.ZonedDateTime} and
     * {@link java.time.OffsetDateTime} (shown in their own zone or offset), {@link java.time.LocalDateTime},
     * {@link java.time.LocalDate} and {@link java.time.LocalTime}; any other {@code TemporalAccessor} is
     * formatted as is. A format that needs a field the value lacks never throws (see the class
     * documentation). The value must be immutable.
     */
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
