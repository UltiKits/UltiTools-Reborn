package com.ultikits.ultitools.uat.fixtures.additionalentities;

import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * A {@code @UltiToolsModule} entry class whose {@code additionalEntities()} names
 * {@link ExternalEntity} -- a class that, in the test using this fixture, is ALSO present in
 * the {@code classes} argument passed to {@code SurfaceAssembler.assemble()} (simulating the
 * runtime-accepted case where a module names a class already on its own classesRoot). Proves
 * the union is de-duplicated rather than concatenated (Phase 10, Codex review of PR #427):
 * {@code PluginManager.scanPluginEntities} de-duplicates through its own {@code HashSet}, so a
 * plain list concatenation here would trip {@code PersistenceRowScanner}'s id-collision guard
 * for a module configuration the runtime accepts without complaint.
 *
 * @since 6.3.0
 */
@UltiToolsModule(additionalEntities = {ExternalEntity.class})
public final class ModuleWithOverlappingAdditionalEntity {
}
