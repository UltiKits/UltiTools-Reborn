package com.ultikits.ultitools.abstracts.gui.declarative.widgets.navigation;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.State;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.Container;
import org.jetbrains.annotations.NotNull;

import java.util.Stack;

/**
 * The state class for Navigator.
 * <p>
 * Manages the route history.
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class NavigatorState extends State<Navigator> {

    private final Stack<String> history = new Stack<>();

    @Override
    public void initState() {
        history.push(getWidget().getInitialRoute());
    }

    /**
     * Pushes a new route.
     *
     * @param routeName the route name
     */
    public void push(@NotNull String routeName) {
        if (!getWidget().getRoutes().containsKey(routeName)) {
            throw new IllegalArgumentException("Route not found: " + routeName);
        }
        setState(() -> history.push(routeName));
    }

    /**
     * Pops the current route.
     * Does nothing if only one route remains.
     */
    public void pop() {
        if (history.size() > 1) {
            setState(history::pop);
        }
    }

    /**
     * Replaces the current route.
     *
     * @param routeName the new route name
     */
    public void pushReplacement(@NotNull String routeName) {
        if (!getWidget().getRoutes().containsKey(routeName)) {
            throw new IllegalArgumentException("Route not found: " + routeName);
        }
        setState(() -> {
            if (!history.isEmpty()) {
                history.pop();
            }
            history.push(routeName);
        });
    }

    /**
     * Checks whether a route can be popped.
     *
     * @return true if the history has more than one entry
     */
    public boolean canPop() {
        return history.size() > 1;
    }

    @Override
    public Widget build(BuildContext context) {
        if (history.isEmpty()) {
            return Container.builder().build();
        }
        String currentRoute = history.peek();
        RouteBuilder builder = getWidget().getRoutes().get(currentRoute);
        if (builder == null) {
            return Container.builder().build();
        }
        return builder.build(context);
    }
}
