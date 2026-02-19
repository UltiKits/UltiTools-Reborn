package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * ComponentElement 是包含子 Widget 的 Element 基类。
 * <p>
 * 它管理一个子 Widget，并在需要时构建它。
 * StatelessWidget 和 StatefulWidget 都使用此类型的 Element。
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
     * 构建子 Widget。
     *
     * @return 子 Widget
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
