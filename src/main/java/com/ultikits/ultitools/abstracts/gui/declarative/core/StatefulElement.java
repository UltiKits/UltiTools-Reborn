package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The Element corresponding to a StatefulWidget.
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class StatefulElement extends ComponentElement {

    @Nullable
    private State<?> _state;

    public StatefulElement(@NotNull StatefulWidget widget) {
        super(widget);
    }

    @Override
    public void mount(@Nullable Element parent) {
        super.mount(parent);

        StatefulWidget widget = (StatefulWidget) getWidget();
        _state = widget.createState();
        _state.setElement(this);
        _state.setWidget(widget);
        _state.setMounted(true);
        _state.initState();
        performRebuild();
    }

    @Override
    public void update(@NotNull Widget newWidget) {
        StatefulWidget oldWidget = (StatefulWidget) getWidget();
        super.update(newWidget);

        if (_state != null) {
            StatefulWidget newStatefulWidget = (StatefulWidget) newWidget;
            _state.setWidget(newStatefulWidget);
            _state.didUpdateWidgetInternal(oldWidget);
        }
    }

    @Override
    public void unmount() {
        if (_state != null) {
            _state.dispose();
            _state.setElement(null);
            _state = null;
        }
        super.unmount();
    }

    @Nullable
    public State<?> getState() {
        return _state;
    }

    @Override
    public Widget build() {
        if (_state == null) {
            throw new IllegalStateException("State is null");
        }
        _state.clearDirty();
        return _state.build(getContext());
    }
}
