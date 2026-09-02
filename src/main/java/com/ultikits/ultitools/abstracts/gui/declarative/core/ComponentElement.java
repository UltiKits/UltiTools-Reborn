package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * ComponentElement is the base Element class for Elements that hold a child Widget.
 * <p>
 * It manages a single child Widget and builds it as needed.
 * Both StatelessWidget and StatefulWidget use this kind of Element.
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public abstract class ComponentElement extends Element {

    @Nullable
    private Element _child;

    public ComponentElement(@NotNull Widget widget) {
        super(widget);
    }

    /**
     * Builds the child Widget.
     *
     * @return the child Widget
     */
    public abstract Widget build();

    @Override
    public void performRebuild() {
        Widget built = build();
        _child = updateChild(built, _child);
        clearDirty();
    }

    @Override
    @NotNull
    public List<Element> getChildren() {
        return _child != null ? Collections.singletonList(_child) : Collections.emptyList();
    }

    @Override
    public void mount(@Nullable Element parent) {
        super.mount(parent);
    }

    @Override
    public void unmount() {
        if (_child != null) {
            _child.unmount();
            _child = null;
        }
        super.unmount();
    }
}
