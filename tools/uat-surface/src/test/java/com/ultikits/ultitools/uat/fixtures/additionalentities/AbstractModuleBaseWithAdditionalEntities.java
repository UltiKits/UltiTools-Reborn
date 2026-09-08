package com.ultikits.ultitools.uat.fixtures.additionalentities;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * An abstract module base carrying {@code @UltiToolsModule(additionalEntities = ...)} --
 * {@link ConcreteModuleNotRedeclaringAdditionalEntities} extends this WITHOUT redeclaring the
 * annotation, the fixture for proving {@code PluginManager.scanPluginEntities}'s direct
 * {@code pluginClass.getAnnotation(UltiToolsModule.class)} lookup (not {@code @Inherited})
 * never sees this base's {@code additionalEntities()} for that concrete subclass (Phase 10,
 * Codex review of PR #427).
 * <p>
 * Extends {@link UltiToolsPlugin} (staying {@code abstract} itself, deliberately -- proving
 * {@code ModuleSwitchReader.findModuleEntryClass}'s abstract-class exclusion still applies when
 * the candidate genuinely is a {@code UltiToolsPlugin} subclass) -- see
 * {@code ModuleWithDefaultScanScope}'s javadoc for why extending it matters at all.
 *
 * @since 6.3.0
 */
@UltiToolsModule(additionalEntities = {ExternalEntity.class})
public abstract class AbstractModuleBaseWithAdditionalEntities extends UltiToolsPlugin {
}
