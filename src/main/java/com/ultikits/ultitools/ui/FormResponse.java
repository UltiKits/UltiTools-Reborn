package com.ultikits.ultitools.ui;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.ApiStatus;

/** Submitted values of a {@link Form}. Immutable. */
@ApiStatus.NonExtendable
public interface FormResponse {
    /**
     * The submitted value of {@code input}. Handles belong by identity, not by key or value equality.
     * @throws IllegalArgumentException if {@code input} is not an input of the form that produced this
     */
    <T> T get(FormInput<T> input);

    /**
     * Creates a response; for backends and tests. Values are checked against each input's type.
     * Every input needs exactly one value, and foreign handles are rejected.
     * @throws IllegalArgumentException if a handle is missing or foreign, or a value has the wrong type
     */
    static FormResponse of(Form form, Map<FormInput<?>, ?> values) {
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(values, "values");
        Map<FormInput<?>, Object> copy = new IdentityHashMap<>();
        values.forEach((input, value) -> copy.put(Objects.requireNonNull(input, "input"),
                Objects.requireNonNull(value, "value")));
        if (copy.size() != form.inputs().size()) {
            throw new IllegalArgumentException("Values must contain exactly the form's input handles");
        }
        for (FormInput<?> input : form.inputs()) {
            if (!copy.containsKey(input) || !hasValidType(input, copy.get(input))) {
                throw new IllegalArgumentException("Missing or incorrectly typed value for input: " + input.key());
            }
        }
        return new FormResponse() {
            @Override
            @SuppressWarnings("unchecked") // Every handle and value pair was checked before publication.
            public <T> T get(FormInput<T> input) {
                Objects.requireNonNull(input, "input");
                if (!copy.containsKey(input)) {
                    throw new IllegalArgumentException("Input does not belong to this form: " + input.key());
                }
                return (T) copy.get(input);
            }

            @Override public String toString() { return "FormResponse[values=" + copy + ']'; }
        };
    }

    private static boolean hasValidType(FormInput<?> input, Object value) {
        return switch (input) {
            case TextInput ignored -> value instanceof String;
            case ToggleInput ignored -> value instanceof Boolean;
            case SliderInput ignored -> value instanceof Double;
            case ChoiceInput<?> choice -> choice.options().stream().anyMatch(option -> option.value().equals(value));
        };
    }
}
