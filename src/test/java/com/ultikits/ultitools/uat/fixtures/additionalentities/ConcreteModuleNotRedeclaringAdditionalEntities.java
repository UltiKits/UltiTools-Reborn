package com.ultikits.ultitools.uat.fixtures.additionalentities;

/**
 * Extends {@link AbstractModuleBaseWithAdditionalEntities} without redeclaring
 * {@code @UltiToolsModule} -- {@code MergedAnnotationResolver.find} still resolves the
 * annotation here (for the registration switches, which the runtime ALSO reads via merged
 * resolution in {@code UltiToolsPlugin.initConfig}), but the base's
 * {@code additionalEntities()} must NOT be attributed to this concrete class, since
 * {@code PluginManager.scanPluginEntities} reads it via a DIRECT, non-inherited
 * {@code getAnnotation} that returns {@code null} here (Phase 10, Codex review of PR #427).
 *
 * @since 6.3.0
 */
public final class ConcreteModuleNotRedeclaringAdditionalEntities extends AbstractModuleBaseWithAdditionalEntities {
}
