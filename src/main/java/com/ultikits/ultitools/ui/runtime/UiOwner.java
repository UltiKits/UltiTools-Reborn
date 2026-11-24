package com.ultikits.ultitools.ui.runtime;

import java.util.Objects;
import org.jetbrains.annotations.ApiStatus;

/**
 * Opaque identity of one module copy. Only the runtime issues these handles; equality is object identity.
 * The name is diagnostic only: two handles with the same name still own different resources.
 */
@ApiStatus.Internal
public final class UiOwner {
    private final String name;

    UiOwner(String name) { this.name = Objects.requireNonNull(name, "name"); }

    /** Returns the module name for diagnostics, never for ownership comparisons. */
    public String name() { return name; }
}
