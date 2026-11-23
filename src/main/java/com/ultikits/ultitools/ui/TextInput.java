package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.text.Text;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/** Free text. Value: the entered string (possibly empty). */
public final class TextInput implements FormInput<String> {
    /** Maximum supported text length. */
    public static final int MAX_LENGTH = 256;
    private final String key;
    private final Text label;
    private final String initial;
    private final int maxLength;
    private final int maxLines;

    private TextInput(Builder builder) {
        key = builder.key;
        label = builder.label;
        initial = builder.initial;
        maxLength = builder.maxLength;
        maxLines = builder.maxLines;
    }

    /** Starts an input with an empty initial string, maximum length 32 and one line. */
    public static Builder builder(String key, Text label) {
        return new Builder(Form.validateKey(key), Objects.requireNonNull(label, "label"));
    }

    @Override public String key() { return key; }
    @Override public Text label() { return label; }
    /** The initial text. */
    public String initial() { return initial; }
    /** Maximum length, 1..{@value #MAX_LENGTH}; default 32. */
    public int maxLength() { return maxLength; }
    /** 1 for single-line; more for a multi-line box. */
    public int maxLines() { return maxLines; }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || o instanceof TextInput other && key.equals(other.key) && label.equals(other.label)
                && initial.equals(other.initial) && maxLength == other.maxLength && maxLines == other.maxLines;
    }

    @Override public int hashCode() { return Objects.hash(key, label, initial, maxLength, maxLines); }
    @Override public String toString() { return "TextInput[key=" + key + ", label=" + label + ", initial=" + initial
            + ", maxLength=" + maxLength + ", maxLines=" + maxLines + ']'; }

    /** A reusable, non-thread-safe builder. */
    public static final class Builder {
        private final String key;
        private final Text label;
        private String initial = "";
        private int maxLength = 32;
        private int maxLines = 1;

        private Builder(String key, Text label) { this.key = key; this.label = label; }
        /** Sets the initial text. */
        public Builder initial(String value) { initial = Objects.requireNonNull(value, "value"); return this; }

        /** @throws IllegalArgumentException outside 1..{@value TextInput#MAX_LENGTH} */
        public Builder maxLength(int maxLength) {
            if (maxLength < 1 || maxLength > MAX_LENGTH) {
                throw new IllegalArgumentException("maxLength must be in 1.." + MAX_LENGTH);
            }
            this.maxLength = maxLength;
            return this;
        }

        /** @throws IllegalArgumentException if less than one */
        public Builder maxLines(int maxLines) {
            if (maxLines < 1) {
                throw new IllegalArgumentException("maxLines must be at least one");
            }
            this.maxLines = maxLines;
            return this;
        }

        /** Builds an immutable input. */
        public TextInput build() { return new TextInput(this); }
    }
}
