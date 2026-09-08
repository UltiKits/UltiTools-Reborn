package com.ultikits.ultitools.uat.fixtures;

import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.annotations.Service;

/**
 * A {@code @Service} carrying {@code @ConditionalOnConfig}, living OUTSIDE
 * {@code com.ultikits.ultitools.uat.fixtures.scanscope} -- when scanned alongside
 * {@code scanscope.ModuleWithDefaultScanScope} (default scan scope its own package only),
 * {@code ComponentScanner.scanPackage} never visits this class at all, so its
 * {@code shouldRegister} gate is never evaluated by the runtime (Codex review of PR #427).
 *
 * @since 6.3.0
 */
@Service
@ConditionalOnConfig(value = "config/config.yml", path = "enableFeature")
public class OutsideScanScopeConditionalService {
}
