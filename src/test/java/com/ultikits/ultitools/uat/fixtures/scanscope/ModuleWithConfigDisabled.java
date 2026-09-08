package com.ultikits.ultitools.uat.fixtures.scanscope;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * A {@code @UltiToolsModule} entry class declaring {@code config = false} --
 * {@code UltiToolsPlugin.initConfig} takes its OTHER branch in that case
 * ({@code getAllConfigs()}, whatever the module's own override returns), which is not
 * package-scanned at all and not statically resolvable without initializing the class. No
 * config scan-package restriction can be safely derived here (Phase 10, Codex review of PR
 * #427).
 * <p>
 * Extends {@link UltiToolsPlugin} -- see {@link ModuleWithDefaultScanScope}'s javadoc for why.
 *
 * @since 6.3.0
 */
@UltiToolsModule(config = false)
public final class ModuleWithConfigDisabled extends UltiToolsPlugin {

    @Override
    public boolean registerSelf() {
        return true;
    }
}
