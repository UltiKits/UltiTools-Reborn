package com.ultikits.ultitools.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;

/**
 * Text rendered only when the viewer's locale is known: a fixed component or a key and arguments.
 * Used for names, titles, labels and lore stored before delivery.
 * The set of permitted subtypes may grow in a 7.x minor release. Code outside the framework
 * must not switch over it exhaustively without a {@code default} branch.
 */
public sealed interface Text permits Text.Literal, Text.Translated {
    /** Returns a fixed component, identical for all locales. */
    static Text literal(ComponentLike component) {
        Objects.requireNonNull(component, "component");
        return new Literal(component.asComponent());
    }

    /** Shorthand for {@code literal(Component.text(plain))}. */
    static Text plain(String plain) {
        return literal(Component.text(Objects.requireNonNull(plain, "plain")));
    }

    /** Returns a deferred message; argument names must be unique. The array is copied. */
    static Text of(MessageKey key, Arg... args) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(args, "args");
        return new Translated(key, Arrays.asList(args));
    }

    /** Returns a deferred message; argument names must be unique. The list is copied. */
    static Text of(MessageKey key, List<? extends Arg> args) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(args, "args");
        return new Translated(key, new ArrayList<>(args));
    }

    /** Fixed text. @param component the immutable Adventure component */
    record Literal(Component component) implements Text {
        /** Rejects a null component. */
        public Literal {
            Objects.requireNonNull(component, "component");
        }
    }

    /**
     * A deferred message with an unmodifiable argument copy.
     * @param key stable translation key
     * @param args uniquely named arguments
     */
    record Translated(MessageKey key, List<Arg> args) implements Text {
        /** @throws IllegalArgumentException if argument names repeat */
        public Translated {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(args, "args");
            Set<String> names = new HashSet<>();
            for (Arg arg : args) {
                Objects.requireNonNull(arg, "arg");
                if (!names.add(arg.name())) {
                    throw new IllegalArgumentException("Duplicate argument name: " + arg.name());
                }
            }
            args = List.copyOf(args);
        }
    }
}
