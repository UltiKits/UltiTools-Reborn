package com.ultikits.ultitools.abstracts.gui.declarative.widgets.navigation;

import com.ultikits.ultitools.abstracts.gui.declarative.core.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The Navigator Widget.
 * <p>
 * Manages a route stack and builds a Widget from the current route.
 * Use {@link Navigator#of(BuildContext)} to obtain the nearest navigator state and navigate.
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class Navigator extends StatefulWidget {

    @NotNull
    private final String initialRoute;
    @NotNull
    private final Map<String, RouteBuilder> routes;

    /**
     * Creates a new Navigator.
     *
     * @param initialRoute the initial route name
     * @param routes       the route table
     */
    public Navigator(@NotNull String initialRoute, @NotNull Map<String, RouteBuilder> routes) {
        this.initialRoute = initialRoute;
        this.routes = routes;
    }

    @Override
    public State<Navigator> createState() {
        return new NavigatorState();
    }

    @NotNull
    public String getInitialRoute() {
        return initialRoute;
    }

    @NotNull
    public Map<String, RouteBuilder> getRoutes() {
        return routes;
    }

    /**
     * Gets the nearest NavigatorState.
     *
     * @param context the BuildContext
     * @return the NavigatorState, or null if none is found
     */
    @Nullable
    public static NavigatorState of(@NotNull BuildContext context) {
        Element current = context.getParentElement();
        while (current != null) {
            if (current instanceof StatefulElement) {
                State<?> state = ((StatefulElement) current).getState();
                if (state instanceof NavigatorState) {
                    return (NavigatorState) state;
                }
            }
            current = current.getParent();
        }
        return null;
    }
}
