package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.*;
import com.ultikits.ultitools.abstracts.gui.declarative.util.WidgetBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Container is a widget that can hold multiple child Widgets.
 * <p>
 * It is responsible for:
 * <ul>
 *   <li>managing the list of child Widgets</li>
 *   <li>assigning slots to child Widgets</li>
 * </ul>
 *
 * <p><strong>Usage example:</strong></p>
 * <pre>{@code
 * Container.builder()
 *     .child(ItemDisplay.builder(item).build())
 *     .child(TextButton.builder().text("Click").build())
 *     .build();
 * }</pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class Container extends Widget {

    @NotNull
    private final List<Widget> children;

    private Container(@NotNull Builder builder) {
        super(builder.key);
        this.children = Collections.unmodifiableList(new ArrayList<>(builder.children));
    }

    /**
     * Creates a new Builder.
     *
     * @return the Builder
     */
    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    @NotNull
    public List<Widget> getChildren() {
        return children;
    }

    @Override
    @NotNull
    public Element createElement() {
        return new ContainerElement(this);
    }

    /**
     * Builder for Container.
     */
    public static class Builder implements WidgetBuilder<Container> {
        private final List<Widget> children = new ArrayList<>();
        @Nullable
        private SlotKey key;

        public Builder child(@NotNull Widget child) {
            this.children.add(child);
            return this;
        }

        public Builder children(@NotNull List<Widget> children) {
            this.children.addAll(children);
            return this;
        }

        public Builder children(@NotNull Widget... children) {
            Collections.addAll(this.children, children);
            return this;
        }

        public Builder key(@Nullable SlotKey key) {
            this.key = key;
            return this;
        }

        public Builder key(@NotNull String key) {
            this.key = SlotKey.of(key);
            return this;
        }

        @Override
        @NotNull
        public Container build() {
            return new Container(this);
        }
    }
}
