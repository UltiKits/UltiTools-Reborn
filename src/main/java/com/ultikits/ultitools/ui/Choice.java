package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * Pick one of a list of text options (no item icons; use {@link Grid} for that). Completes with the value.
 * @param <T> answer type; values must be immutable
 */
public final class Choice<T> implements UiIntent<T> {
    private final Text title;
    private final List<Text> body;
    private final List<ChoiceOption<T>> options;

    private Choice(Builder<T> builder, List<ChoiceOption<T>> options) {
        title = builder.title;
        body = List.copyOf(builder.body);
        this.options = options;
    }

    /** @throws IllegalArgumentException at build if empty or values are not unique */
    public static <T> Builder<T> builder(Text title) { return new Builder<>(Objects.requireNonNull(title, "title")); }
    @Override public Text title() { return title; }
    /** Body lines in display order; unmodifiable. */
    public List<Text> body() { return body; }
    /** Options in display order; unmodifiable. */
    public List<ChoiceOption<T>> options() { return options; }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || o instanceof Choice<?> other && title.equals(other.title)
                && body.equals(other.body) && options.equals(other.options);
    }

    @Override public int hashCode() { return Objects.hash(title, body, options); }
    @Override public String toString() { return "Choice[title=" + title + ", body=" + body + ", options=" + options + ']'; }

    /**
     * A reusable, non-thread-safe builder.
     * @param <T> answer type
     */
    public static final class Builder<T> {
        private final Text title;
        private final List<Text> body = new ArrayList<>();
        private final List<ChoiceOption<T>> options = new ArrayList<>();

        private Builder(Text title) { this.title = title; }

        /** Replaces the body with a defensive copy. */
        public Builder<T> body(List<? extends Text> lines) {
            List<Text> copy = Form.copyBody(lines);
            body.clear();
            body.addAll(copy);
            return this;
        }

        /** Appends one line. */
        public Builder<T> addBody(Text line) { body.add(Objects.requireNonNull(line, "line")); return this; }
        /** Appends an option without a tooltip. */
        public Builder<T> option(T value, Text label) { return option(ChoiceOption.of(value, label)); }
        /** Appends an option. */
        public Builder<T> option(ChoiceOption<T> option) { options.add(Objects.requireNonNull(option, "option")); return this; }
        /** @throws IllegalArgumentException if empty or values are not unique */
        public Choice<T> build() { return new Choice<>(this, Form.copyOptions(options)); }
    }
}
