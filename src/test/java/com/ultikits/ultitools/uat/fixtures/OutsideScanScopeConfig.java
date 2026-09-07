package com.ultikits.ultitools.uat.fixtures;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * A valid {@code @ConfigEntity} living OUTSIDE
 * {@code com.ultikits.ultitools.uat.fixtures.scanscope} -- when scanned alongside
 * {@code scanscope.ModuleWithDefaultScanScope} (registersConfig true, default scan scope its
 * own package only), {@code ConfigManager.registerAll} (via {@code DependencyUtils
 * .getPluginPackages}) would never discover this class at runtime, so
 * {@code SurfaceAssembler} must emit no row for it either (Codex review of PR #427).
 *
 * @since 6.3.0
 */
@ConfigEntity("config/outsidescope.yml")
public class OutsideScanScopeConfig {
    @ConfigEntry(path = "enabled")
    private boolean enabled = true;
}
