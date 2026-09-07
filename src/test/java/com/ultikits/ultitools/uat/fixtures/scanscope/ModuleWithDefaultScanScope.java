package com.ultikits.ultitools.uat.fixtures.scanscope;

import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * A {@code @UltiToolsModule} entry class declaring neither {@code scanBasePackages()} nor
 * {@code scanBasePackageClasses()} -- {@code PluginManager.getPluginScanPackages} defaults to
 * this class's OWN package in that case, so only classes under
 * {@code com.ultikits.ultitools.uat.fixtures.scanscope} are ever registered by
 * {@code ComponentScanner} for this module (Phase 10, Codex review of PR #427).
 *
 * @since 6.3.0
 */
@UltiToolsModule
public final class ModuleWithDefaultScanScope {
}
