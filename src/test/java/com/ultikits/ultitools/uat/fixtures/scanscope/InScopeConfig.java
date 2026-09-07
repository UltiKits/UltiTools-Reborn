package com.ultikits.ultitools.uat.fixtures.scanscope;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * A valid {@code @ConfigEntity} living in the SAME package as
 * {@link ModuleWithDefaultScanScope} -- covered by that module's default (own-package) config
 * scan scope, so this entity's row must be present (Phase 10, Codex review of PR #427).
 *
 * @since 6.3.0
 */
@ConfigEntity("config/inscope.yml")
public class InScopeConfig {
    @ConfigEntry(path = "enabled")
    private boolean enabled = true;
}
