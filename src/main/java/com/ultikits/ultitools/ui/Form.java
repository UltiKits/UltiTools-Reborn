package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * A form with one or more inputs, submitted at once. Completes with the submitted values.
 * There is no masked (password) input: Paper 26.3's dialog text input has no such option.
 */
public final class Form implements UiIntent<FormResponse> {
    private final Text title;
    private final List<Text> body;
    private final List<FormInput<?>> inputs;
    private final Text submit;

    private Form(Builder builder) {
        title = builder.title;
        body = List.copyOf(builder.body);
        inputs = List.copyOf(builder.inputs);
        submit = builder.submit;
    }

    /** @throws IllegalArgumentException at build if there are no inputs or two inputs share a key */
    public static Builder builder(Text title) { return new Builder(Objects.requireNonNull(title, "title")); }
    @Override public Text title() { return title; }
    /** Body lines; unmodifiable. */
    public List<Text> body() { return body; }
    /** Typed handles in display order; unmodifiable. */
    public List<FormInput<?>> inputs() { return inputs; }
    /** Defaults to {@code ultitools:ui.submit}. */
    public Text submit() { return submit; }

    static String validateKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.length() > 32 || !key.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("key must match [a-z0-9_]+ and be at most 32 characters");
        }
        return key;
    }

    static List<Text> copyBody(List<? extends Text> lines) {
        Objects.requireNonNull(lines, "lines");
        for (Text line : lines) {
            Objects.requireNonNull(line, "line");
        }
        return List.copyOf(lines);
    }

    static <T> List<ChoiceOption<T>> copyOptions(List<ChoiceOption<T>> options) {
        Objects.requireNonNull(options, "options");
        if (options.isEmpty()) {
            throw new IllegalArgumentException("options must not be empty");
        }
        Set<T> values = new HashSet<>();
        for (ChoiceOption<T> option : options) {
            Objects.requireNonNull(option, "option");
            if (!values.add(option.value())) {
                throw new IllegalArgumentException("Duplicate option value: " + option.value());
            }
        }
        return List.copyOf(options);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || o instanceof Form other && title.equals(other.title)
                && body.equals(other.body) && inputs.equals(other.inputs) && submit.equals(other.submit);
    }

    @Override public int hashCode() { return Objects.hash(title, body, inputs, submit); }
    @Override public String toString() { return "Form[title=" + title + ", body=" + body + ", inputs=" + inputs + ", submit=" + submit + ']'; }

    /** A reusable, non-thread-safe builder. */
    public static final class Builder {
        private final Text title;
        private final List<Text> body = new ArrayList<>();
        private final List<FormInput<?>> inputs = new ArrayList<>();
        private Text submit = Text.of(MessageKey.of("ultitools", "ui.submit"));

        private Builder(Text title) { this.title = title; }

        /** Replaces the body with a defensive copy. */
        public Builder body(List<? extends Text> lines) {
            List<Text> copy = copyBody(lines);
            body.clear();
            body.addAll(copy);
            return this;
        }

        /** Appends one line. */
        public Builder addBody(Text line) { body.add(Objects.requireNonNull(line, "line")); return this; }
        /** Appends a typed input handle. Keys must be unique at build time. */
        public Builder input(FormInput<?> input) { inputs.add(Objects.requireNonNull(input, "input")); return this; }
        /** Sets the submit label. */
        public Builder submit(Text label) { submit = Objects.requireNonNull(label, "label"); return this; }

        /** @throws IllegalArgumentException if there are no inputs or two inputs share a key */
        public Form build() {
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException("A form needs at least one input");
            }
            Set<String> keys = new HashSet<>();
            for (FormInput<?> input : inputs) {
                if (!keys.add(input.key())) {
                    throw new IllegalArgumentException("Duplicate input key: " + input.key());
                }
            }
            return new Form(this);
        }
    }
}
