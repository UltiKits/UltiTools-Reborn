package com.ultikits.ultitools.ui;

import java.util.Objects;

/** One slot's view of one container index. A record: the shape is final. */
public record SlotBinding(SlotContainer container, int index, BindingMode mode) {
    /** @throws IllegalArgumentException if {@code index} is outside {@code 0..container.size()-1} */
    public SlotBinding {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(mode, "mode");
        if (index < 0 || index >= container.size()) {
            throw new IllegalArgumentException("index is outside the container");
        }
    }
}
