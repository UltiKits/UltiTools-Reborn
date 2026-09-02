package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;

/**
 * StatefulWidget is a Widget that carries mutable state.
 * <p>
 * It separates the Widget's configuration (fixed by its constructor arguments) from its state
 * (managed by a State object). When {@link State#setState(VoidCallback)} is called, the framework
 * schedules a rebuild using a new StatefulWidget instance while keeping the same State object.
 * <p>
 * <b>When to use it:</b>
 * <ul>
 *   <li>UI that needs user interaction (button clicks, form input)</li>
 *   <li>UI that needs to manage internal state (expand/collapse, selection state)</li>
 *   <li>UI that needs to subscribe to an external data source (live data updates)</li>
 * </ul>
 *
 * <p><strong>Lifecycle:</strong></p>
 * <ol>
 *   <li>{@link State#initState()} - initializes state, called exactly once</li>
 *   <li>{@link State#build(BuildContext)} - builds the UI, may be called multiple times</li>
 *   <li>{@link State#didUpdateWidget(StatefulWidget)} - called when the configuration changes</li>
 *   <li>{@link State#dispose()} - releases resources, called exactly once</li>
 * </ol>
 *
 * <p><strong>Usage example:</strong></p>
 * <pre>{@code
 * public class CounterButton extends StatefulWidget {
 *     @Override
 *     public State<CounterButton> createState() {
 *         return new CounterState();
 *     }
 * }
 *
 * class CounterState extends State<CounterButton> {
 *     private int count = 0;
 *
 *     @Override
 *     void initState() {
 *         super.initState();
 *         // initialization work
 *     }
 *
 *     @Override
 *     Widget build(BuildContext context) {
 *         return TextButton.builder()
 *             .text("Clicked: " + count)
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
 * @see State
 */
public abstract class StatefulWidget extends Widget {

    /**
     * Creates a new StatefulWidget.
     */
    protected StatefulWidget() {
        super();
    }

    /**
     * Creates a new StatefulWidget with the given key.
     *
     * @param key the key used to stably identify this Widget
     */
    protected StatefulWidget(SlotKey key) {
        super(key);
    }

    /**
     * Creates the state object associated with this Widget.
     * <p>
     * The framework calls this method the first time the Widget is inserted into the tree. The
     * same State object is reused across Widget rebuilds.
     *
     * @return the new State instance
     */
    @NotNull
    public abstract State<? extends StatefulWidget> createState();

    @Override
    @NotNull
    public Element createElement() {
        return new StatefulElement(this);
    }
}
