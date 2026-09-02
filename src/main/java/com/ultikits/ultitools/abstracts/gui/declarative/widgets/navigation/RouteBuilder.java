package com.ultikits.ultitools.abstracts.gui.declarative.widgets.navigation;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import org.jetbrains.annotations.NotNull;

/**
 * Route builder interface.
 * <p>
 * Used to build a Widget from a context.
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
@FunctionalInterface
public interface RouteBuilder {

    /**
     * Builds the Widget.
     *
     * @param context the BuildContext
     * @return the built Widget
     */
    @NotNull
    Widget build(@NotNull BuildContext context);
}
