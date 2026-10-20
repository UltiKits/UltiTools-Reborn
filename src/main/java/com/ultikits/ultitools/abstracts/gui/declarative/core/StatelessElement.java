package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The Element corresponding to a StatelessWidget.
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class StatelessElement extends ComponentElement {

    public StatelessElement(@NotNull StatelessWidget widget) {
        super(widget);
    }

    @Override
    public void mount(@Nullable Element parent) {
        super.mount(parent);
        performRebuild();
    }

    @Override
    public Widget build() {
        return ((StatelessWidget) getWidget()).build(getContext());
    }
}
