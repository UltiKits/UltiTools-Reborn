package com.ultikits.ultitools.utils;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Answers which custom game content a module added, for removal warnings.
 *
 * <p>The bootstrap content channels supply the real answer; until they install one, {@link #NONE}
 * answers "nothing" for every module.
 *
 * @since 7.0.0
 */
@ApiStatus.Internal
@FunctionalInterface
public interface ModuleContentLedger {

    /** Answers {@link ModuleContent#NONE} for every module. */
    ModuleContentLedger NONE = (identifyString, moduleName) -> ModuleContent.NONE;

    /**
     * Returns the custom content the module added.
     *
     * @param identifyString the module's normalised identify string, or {@code null} when unknown
     * @param moduleName     the {@code name} from the module's {@code plugin.yml}; the primary lookup key
     * @return the content; never {@code null}
     */
    ModuleContent contentOf(@Nullable String identifyString, String moduleName);
}
