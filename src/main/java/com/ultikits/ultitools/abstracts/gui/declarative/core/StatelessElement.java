package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * StatelessWidget 对应的 Element。
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
