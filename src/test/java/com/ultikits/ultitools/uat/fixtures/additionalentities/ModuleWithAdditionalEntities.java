package com.ultikits.ultitools.uat.fixtures.additionalentities;

import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * A {@code @UltiToolsModule} entry class declaring {@link ExternalEntity} via
 * {@code additionalEntities()} -- the fixture for proving
 * {@code SurfaceAssembler.assemble()} includes an externally-declared persistence entity in
 * its persistence scan even when that class is absent from the {@code classes} argument
 * enumerated from the module's own {@code classesRoot} (Phase 10, Codex review of PR #427).
 *
 * @since 6.3.0
 */
@UltiToolsModule(additionalEntities = {ExternalEntity.class})
public final class ModuleWithAdditionalEntities {
}
