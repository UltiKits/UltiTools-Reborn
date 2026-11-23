package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.text.Text;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * Pick exactly one of several options. Value: the chosen option's value.
 * @param <T> value type
 */
public final class ChoiceInput<T> implements FormInput<T> {
    private final String key;
    private final Text label;
    private final List<ChoiceOption<T>> options;
    private final T initial;

    private ChoiceInput(String key, Text label, List<ChoiceOption<T>> options, T initial) {
        this.key = key;
        this.label = label;
        this.options = options;
        this.initial = initial;
    }

    /** Uses the first option initially. @throws IllegalArgumentException if empty or values repeat */
    public static <T> ChoiceInput<T> of(String key, Text label, List<ChoiceOption<T>> options) {
        String checkedKey = Form.validateKey(key);
        Objects.requireNonNull(label, "label");
        List<ChoiceOption<T>> copy = Form.copyOptions(options);
        return new ChoiceInput<>(checkedKey, label, copy, copy.getFirst().value());
    }

    /** {@code initial} must be one of the options' values. */
    public static <T> ChoiceInput<T> of(String key, Text label, List<ChoiceOption<T>> options, T initial) {
        String checkedKey = Form.validateKey(key);
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(initial, "initial");
        List<ChoiceOption<T>> copy = Form.copyOptions(options);
        if (copy.stream().noneMatch(option -> option.value().equals(initial))) {
            throw new IllegalArgumentException("initial must be one of the option values");
        }
        return new ChoiceInput<>(checkedKey, label, copy, initial);
    }

    @Override public String key() { return key; }
    @Override public Text label() { return label; }
    /** Options in display order; unmodifiable. */
    public List<ChoiceOption<T>> options() { return options; }
    /** The initial option value. */
    public T initial() { return initial; }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || o instanceof ChoiceInput<?> other && key.equals(other.key)
                && label.equals(other.label) && options.equals(other.options) && initial.equals(other.initial);
    }

    @Override public int hashCode() { return Objects.hash(key, label, options, initial); }
    @Override public String toString() { return "ChoiceInput[key=" + key + ", label=" + label + ", options=" + options + ", initial=" + initial + ']'; }
}
