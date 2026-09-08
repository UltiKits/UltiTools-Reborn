package com.ultikits.ultitools.uat.fixtures.additionalentities;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.EnableAutoRegister;

/**
 * A concrete {@link UltiToolsPlugin} subclass using the supported direct
 * {@code @EnableAutoRegister} annotation instead of {@code @UltiToolsModule} -- a module the
 * runtime still auto-registers commands and listeners for
 * ({@code PluginManager.registerBukkit}'s own {@code MergedAnnotationResolver.find(...,
 * EnableAutoRegister.class)} resolves this directly, with no {@code @UltiToolsModule} anywhere
 * in the picture), but which {@code ModuleSwitchReader.findModuleEntryClass}'s OLD
 * {@code @UltiToolsModule}-requiring predicate would have returned {@code null} for entirely
 * (Phase 10, Codex review of PR #427). Carries no {@code additionalEntities()} at all -- only
 * {@code @UltiToolsModule} declares that attribute.
 *
 * @since 6.3.0
 */
@EnableAutoRegister(cmdExecutor = true, eventListener = false, config = true)
public final class ModuleWithBareEnableAutoRegister extends UltiToolsPlugin {

    @Override
    public boolean registerSelf() {
        return true;
    }
}
