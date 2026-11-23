package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/** A message with one acknowledge button. Completes with {@link Ack#ACK} when acknowledged. */
public final class Notice implements UiIntent<Ack> {
    private final Text title;
    private final List<Text> body;
    private final Text button;

    private Notice(Builder builder) {
        title = builder.title;
        body = List.copyOf(builder.body);
        button = builder.button;
    }

    /** Starts an immutable message description. */
    public static Builder builder(Text title) {
        return new Builder(Objects.requireNonNull(title, "title"));
    }

    @Override public Text title() { return title; }
    /** Body lines, unmodifiable and in display order. */
    public List<Text> body() { return body; }
    /** Button label; defaults to the framework message {@code ultitools:ui.ok}. */
    public Text button() { return button; }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || o instanceof Notice other && title.equals(other.title)
                && body.equals(other.body) && button.equals(other.button);
    }

    @Override public int hashCode() { return Objects.hash(title, body, button); }
    @Override public String toString() { return "Notice[title=" + title + ", body=" + body + ", button=" + button + ']'; }

    /** A reusable, non-thread-safe builder; builds are independent snapshots. */
    public static final class Builder {
        private final Text title;
        private final List<Text> body = new ArrayList<>();
        private Text button = Text.of(MessageKey.of("ultitools", "ui.ok"));

        private Builder(Text title) { this.title = title; }

        /** Replaces the body with a defensive copy. */
        public Builder body(List<? extends Text> lines) {
            List<Text> copy = Form.copyBody(lines);
            body.clear();
            body.addAll(copy);
            return this;
        }

        /** Appends one line. */
        public Builder addBody(Text line) {
            body.add(Objects.requireNonNull(line, "line"));
            return this;
        }

        /** Sets the acknowledge button label. */
        public Builder button(Text label) {
            button = Objects.requireNonNull(label, "label");
            return this;
        }

        /** Builds an immutable snapshot. */
        public Notice build() { return new Notice(this); }
    }
}
