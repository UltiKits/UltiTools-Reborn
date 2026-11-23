package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.text.Text;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/** A number from a range. Value: the chosen number, a multiple of {@code step} from {@code min}. */
public final class SliderInput implements FormInput<Double> {
    private final String key;
    private final Text label;
    private final double min;
    private final double max;
    private final double step;
    private final double initial;

    private SliderInput(Builder builder) {
        key = builder.key;
        label = builder.label;
        min = builder.min;
        max = builder.max;
        step = builder.step;
        initial = builder.initial;
    }

    /** Starts a finite range, initially at its minimum, with step 1. */
    public static Builder builder(String key, Text label, double min, double max) {
        return new Builder(Form.validateKey(key), Objects.requireNonNull(label, "label"), min, max);
    }

    @Override public String key() { return key; }
    @Override public Text label() { return label; }
    /** Inclusive minimum. */
    public double min() { return min; }
    /** Inclusive maximum. */
    public double max() { return max; }
    /** Positive increment from the minimum. */
    public double step() { return step; }
    /** Initial value within the range. */
    public double initial() { return initial; }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || o instanceof SliderInput other && key.equals(other.key) && label.equals(other.label)
                && Double.compare(min, other.min) == 0 && Double.compare(max, other.max) == 0
                && Double.compare(step, other.step) == 0 && Double.compare(initial, other.initial) == 0;
    }

    @Override public int hashCode() { return Objects.hash(key, label, min, max, step, initial); }
    @Override public String toString() { return "SliderInput[key=" + key + ", label=" + label + ", min=" + min
            + ", max=" + max + ", step=" + step + ", initial=" + initial + ']'; }

    /** A reusable, non-thread-safe builder. */
    public static final class Builder {
        private final String key;
        private final Text label;
        private final double min;
        private final double max;
        private double step = 1;
        private double initial;

        private Builder(String key, Text label, double min, double max) {
            this.key = key;
            this.label = label;
            this.min = min;
            this.max = max;
            initial = min;
        }

        /** @throws IllegalArgumentException if not positive and finite */
        public Builder step(double step) {
            if (!Double.isFinite(step) || !(step > 0)) {
                throw new IllegalArgumentException("step must be positive and finite");
            }
            this.step = step;
            return this;
        }

        /** @throws IllegalArgumentException if outside [min, max] or not finite */
        public Builder initial(double initial) {
            if (!Double.isFinite(initial) || !(initial >= min && initial <= max)) {
                throw new IllegalArgumentException("initial must be within [min, max]");
            }
            this.initial = initial;
            return this;
        }

        /** @throws IllegalArgumentException if {@code min >= max} or a bound is not finite */
        public SliderInput build() {
            if (!Double.isFinite(min) || !Double.isFinite(max) || !(min < max)) {
                throw new IllegalArgumentException("min and max must be finite with min < max");
            }
            return new SliderInput(this);
        }
    }
}
