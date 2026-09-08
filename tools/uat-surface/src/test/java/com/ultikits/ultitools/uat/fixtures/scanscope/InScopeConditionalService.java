package com.ultikits.ultitools.uat.fixtures.scanscope;

import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.annotations.Service;

/**
 * A {@code @Service} carrying {@code @ConditionalOnConfig}, living in the SAME package as
 * {@link ModuleWithDefaultScanScope} -- covered by that module's default (own-package)
 * component-scan scope, so its standalone conditional row and gate must be present (Phase 10,
 * Codex review of PR #427).
 *
 * @since 6.3.0
 */
@Service
@ConditionalOnConfig(value = "config/config.yml", path = "enableFeature")
public class InScopeConditionalService {
}
