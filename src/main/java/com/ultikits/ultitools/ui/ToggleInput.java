package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.text.Text;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/** A checkbox. Value: its state. */
public final class ToggleInput implements FormInput<Boolean> {
    private final String key;
    private final Text label;
    private final boolean initial;

    private ToggleInput(String key, Text label, boolean initial) {
        this.key = key;
        this.label = label;
        this.initial = initial;
    }

    /** Creates a checkbox with a validated key. */
    public static ToggleInput of(String key, Text label, boolean initial) {
        return new ToggleInput(Form.validateKey(key), Objects.requireNonNull(label, "label"), initial);
    }

    @Override public String key() { return key; }
    @Override public Text label() { return label; }
    /** The initial state. */
    public boolean initial() { return initial; }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || o instanceof ToggleInput other && key.equals(other.key)
                && label.equals(other.label) && initial == other.initial;
    }

    @Override public int hashCode() { return Objects.hash(key, label, initial); }
    @Override public String toString() { return "ToggleInput[key=" + key + ", label=" + label + ", initial=" + initial + ']'; }
}
