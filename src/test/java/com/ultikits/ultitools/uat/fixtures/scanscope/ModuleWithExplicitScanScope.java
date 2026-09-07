package com.ultikits.ultitools.uat.fixtures.scanscope;

import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * A {@code @UltiToolsModule} entry class declaring an EXPLICIT {@code scanBasePackages()} that
 * does NOT include this class's own package -- proving the default-own-package fallback only
 * applies when nothing is declared, matching {@code PluginManager.getPluginScanPackages}
 * exactly (Phase 10, Codex review of PR #427).
 *
 * @since 6.3.0
 */
@UltiToolsModule(scanBasePackages = "com.ultikits.ultitools.uat.fixtures.scanscope.declaredpackage")
public final class ModuleWithExplicitScanScope {
}
