package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Widget is the core abstract class of the declarative UI framework.
 * <p>
 * A Widget is an immutable, lightweight configuration object that describes part of the UI.
 * Every state change creates a new Widget instance, but the framework reuses Elements
 * efficiently through the diff algorithm.
 * <p>
 * Core idea: <b>UI = f(state)</b>
 * <p>
 * A Widget itself holds no mutable state and never touches an Inventory directly.
 * It only describes "what should be shown", never "how" or "where" it is shown.
 *
 * <p><strong>Subclass types:</strong></p>
 * <ul>
 *   <li>{@link StatelessWidget} - a stateless, purely functional Widget that depends only on its input parameters</li>
 *   <li>{@link StatefulWidget} - a stateful Widget that can rebuild in response to setState</li>
 *   <li>{@link RenderObjectWidget} - a render Widget, corresponding to an actual RenderNode</li>
 * </ul>
 *
 * <p><strong>Usage example:</strong></p>
 * <pre>{@code
 * // Create a counter Widget
 * public class CounterWidget extends StatefulWidget {
 *     @Override
 *     public State createState() {
 *         return new CounterState();
 *     }
 * }
 *
 * class CounterState extends State<CounterWidget> {
 *     private int count = 0;
 *
 *     @Override
 *     public Widget build(BuildContext context) {
 *         return TextButton.builder()
 *             .text("Count: " + count)
 *             .onClick(() -> setState(() -> count++))
 *             .build();
 *     }
 * }
 * }</pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 * @see StatelessWidget
 * @see StatefulWidget
 * @see Element
 */
public abstract class Widget {

    /**
     * The optional key used to stably identify this Widget within a list.
     * <p>
     * When a Widget sits inside a list, it should supply a stable key so the framework can
     * recognize which Widgets are the same one, which optimizes diff performance.
     */
    @Nullable
    private final SlotKey key;

    /**
     * Creates a new Widget instance.
     */
    protected Widget() {
        this(null);
    }

    /**
     * Creates a new Widget instance with the given key.
     *
     * @param key the key used to stably identify this Widget
     */
    protected Widget(@Nullable SlotKey key) {
        this.key = key;
    }

    /**
     * Gets this Widget's key.
     *
     * @return the key, or null if none was set
     */
    @Nullable
    public SlotKey getKey() {
        return key;
    }

    /**
     * Creates the Element corresponding to this Widget.
     * <p>
     * This is a framework-internal method; subclasses should return the Element implementation
     * appropriate to the Widget type.
     * <ul>
     *   <li>StatelessWidget returns a {@link StatelessElement}</li>
     *   <li>StatefulWidget returns a {@link StatefulElement}</li>
     *   <li>RenderObjectWidget returns a {@link RenderObjectElement}</li>
     * </ul>
     *
     * @return the corresponding Element instance
     */
    @NotNull
    public abstract Element createElement();

    /**
     * Checks whether this Widget can update the given Element.
     * <p>
     * The default implementation checks whether the runtime types match.
     * Subclasses may override this to provide custom update logic.
     *
     * @param element the Element to check
     * @return true if the Element can be updated
     */
    public boolean canUpdate(@NotNull Element element) {
        return element.getWidget().getClass() == this.getClass();
    }

    /**
     * Checks whether two Widgets represent the same configuration.
     * <p>
     * Two Widgets are considered the same Widget when their keys match and their runtime types
     * match. This is used by the diff algorithm to decide whether an Element can be reused.
     *
     * @param other the other Widget
     * @return true if they are the same
     */
    public boolean isSameWidget(@Nullable Widget other) {
        if (this == other) return true;
        if (other == null) return false;
        if (this.getClass() != other.getClass()) return false;
        return Objects.equals(this.key, other.key);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Widget)) return false;
        Widget widget = (Widget) o;
        return Objects.equals(key, widget.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key);
    }

    @Override
    public String toString() {
        String keyString = key != null ? " key=" + key : "";
        return getClass().getSimpleName() + "(" + keyString + ")";
    }
}

