package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.text.Text;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * One option of a {@link Choice} or {@link ChoiceInput}: a value and how it is shown.
 * The caller must supply an immutable value to keep the containing intent immutable.
 * @param <T> value type
 * @param value the non-null answer
 * @param label the displayed label
 * @param tooltip optional explanatory text
 */
public record ChoiceOption<T>(T value, Text label, @Nullable Text tooltip) {
    /** Rejects a null value or label. */
    public ChoiceOption {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(label, "label");
    }

    /** Creates an option without a tooltip. */
    public static <T> ChoiceOption<T> of(T value, Text label) { return new ChoiceOption<>(value, label, null); }
}
