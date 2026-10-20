package com.ultikits.ultitools.abstracts.gui.declarative.util;

import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import org.jetbrains.annotations.NotNull;

/**
 * Common interface for Widget builders.
 *
 * @param <T> the type of Widget being built
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
@FunctionalInterface
public interface WidgetBuilder<T extends Widget> {

    /**
     * Builds the Widget instance.
     *
     * @return the Widget instance
     */
    @NotNull
    T build();
}
