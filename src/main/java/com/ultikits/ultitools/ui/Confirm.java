package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * A yes/no question. Completes with {@code true} for yes. A backend that cannot tell "no" from dismissal
 * reports {@code false}; callers must treat anything other than {@code Completed(true)} as "not confirmed".
 */
public final class Confirm implements UiIntent<Boolean> {
    private final Text title;
    private final List<Text> body;
    private final Text yes;
    private final Text no;

    private Confirm(Builder builder) {
        title = builder.title;
        body = List.copyOf(builder.body);
        yes = builder.yes;
        no = builder.no;
    }

    /** Starts a question description. */
    public static Builder builder(Text title) { return new Builder(Objects.requireNonNull(title, "title")); }
    @Override public Text title() { return title; }
    /** Body lines in display order; unmodifiable. */
    public List<Text> body() { return body; }
    /** Defaults to {@code ultitools:ui.yes}. */
    public Text yes() { return yes; }
    /** Defaults to {@code ultitools:ui.no}. */
    public Text no() { return no; }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || o instanceof Confirm other && title.equals(other.title)
                && body.equals(other.body) && yes.equals(other.yes) && no.equals(other.no);
    }

    @Override public int hashCode() { return Objects.hash(title, body, yes, no); }
    @Override public String toString() { return "Confirm[title=" + title + ", body=" + body + ", yes=" + yes + ", no=" + no + ']'; }

    /** A reusable, non-thread-safe builder. */
    public static final class Builder {
        private final Text title;
        private final List<Text> body = new ArrayList<>();
        private Text yes = Text.of(MessageKey.of("ultitools", "ui.yes"));
        private Text no = Text.of(MessageKey.of("ultitools", "ui.no"));

        private Builder(Text title) { this.title = title; }

        /** Replaces the body with a defensive copy. */
        public Builder body(List<? extends Text> lines) {
            List<Text> copy = Form.copyBody(lines);
            body.clear();
            body.addAll(copy);
            return this;
        }

        /** Appends one line. */
        public Builder addBody(Text line) { body.add(Objects.requireNonNull(line, "line")); return this; }
        /** Sets the affirmative label. */
        public Builder yes(Text label) { yes = Objects.requireNonNull(label, "label"); return this; }
        /** Sets the negative label. */
        public Builder no(Text label) { no = Objects.requireNonNull(label, "label"); return this; }
        /** Builds an immutable snapshot. */
        public Confirm build() { return new Confirm(this); }
    }
}
