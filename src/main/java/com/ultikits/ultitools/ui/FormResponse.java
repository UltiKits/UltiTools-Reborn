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
     * Two equal forms do not accept each other's handles: read values with the handles of the form that was shown.
     * @param <T> input value type
     * @throws IllegalArgumentException if {@code input} is not an input of the form that produced this
     */
    <T> T get(FormInput<T> input);

    /**
     * Creates a response; for backends and tests. Every input of {@code form} needs exactly one value, keyed by
     * that input's handle (by identity); foreign handles are rejected. Each value is checked against its input:
     * <ul>
     *   <li>{@link TextInput}: a {@code String} of at most {@code maxLength()} characters ({@link String#length()})
     *       with at most {@code maxLines() - 1} line feeds;</li>
     *   <li>{@link ToggleInput}: a {@code Boolean};</li>
     *   <li>{@link ChoiceInput}: equal to one option's value;</li>
     *   <li>{@link SliderInput}: a finite {@code Double} no further than half a step outside [min, max]; it is
     *       stored snapped to the nearest min + k * step, and as max itself for the largest k, so
     *       {@link #get} returns a value of the slider's documented form.</li>
     * </ul>
     * A backend whose submitted answer this rejects must not complete the session with it: it logs one WARNING
     * with the owner and reports {@code closed(PLAYER)}.
     * @throws IllegalArgumentException if a handle is missing or foreign, or a value fails its input's check
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
            if (!copy.containsKey(input)) {
                throw new IllegalArgumentException("Missing or incorrectly typed value for input: " + input.key());
            }
            copy.put(input, checkedValue(input, copy.get(input)));
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

    private static Object checkedValue(FormInput<?> input, Object value) {
        boolean valid = switch (input) {
            case TextInput text -> value instanceof String string && string.length() <= text.maxLength()
                    && string.chars().filter(c -> c == '\n').count() <= text.maxLines() - 1;
            case ToggleInput ignored -> value instanceof Boolean;
            case SliderInput ignored -> value instanceof Double number && Double.isFinite(number);
            case ChoiceInput<?> choice -> choice.options().stream().anyMatch(option -> option.value().equals(value));
        };
        if (!valid) {
            throw new IllegalArgumentException("Invalid value for input: " + input.key());
        }
        return input instanceof SliderInput slider ? snapped(slider, (Double) value) : value;
    }

    private static double snapped(SliderInput slider, double value) {
        double min = slider.min();
        double max = slider.max();
        double step = slider.step();
        long n = SliderInput.steps(min, max, step);
        if (value < min - step / 2 || value > max + step / 2) {
            throw new IllegalArgumentException("Slider value is outside the half-step tolerance");
        }
        long k = Math.round((value - min) / step);
        k = Math.max(0, Math.min(n, k));
        return k == n ? max : min + k * step;
    }
}
