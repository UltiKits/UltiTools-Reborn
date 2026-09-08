package com.ultikits.ultitools.uat.fixtures.additionalentities;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * A concrete {@link UltiToolsPlugin} subclass carrying NEITHER {@code @UltiToolsModule} nor
 * {@code @EnableAutoRegister} anywhere in its hierarchy -- still a valid module entry point per
 * {@code PluginManager.loadPluginMainClass}'s own runtime predicate (a concrete,
 * non-{@code abstract}, non-interface {@code UltiToolsPlugin} subclass), but
 * {@code PluginManager.registerBukkit} resolves {@code EnableAutoRegister} to {@code null} for
 * it and returns EARLY, registering NEITHER commands NOR listeners at all (Phase 10, Codex
 * review of PR #427).
 *
 * @since 6.3.0
 */
public final class ModuleWithNoRelevantAnnotationAtAll extends UltiToolsPlugin {

    @Override
    public boolean registerSelf() {
        return true;
    }
}
