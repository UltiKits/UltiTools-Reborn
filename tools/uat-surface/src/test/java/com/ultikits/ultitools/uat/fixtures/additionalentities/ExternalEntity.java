package com.ultikits.ultitools.uat.fixtures.additionalentities;

import com.ultikits.ultitools.annotations.Table;

/**
 * A {@code @Table} class standing in for an entity that lives outside a module's own
 * {@code classesRoot} -- a shared library jar, or a multi-module build's common artifact
 * (Phase 10, Codex review of PR #427: {@code @UltiToolsModule.additionalEntities()}).
 * <p>
 * It is deliberately never passed to {@code SurfaceAssembler.assemble()}'s own {@code classes}
 * argument in the test that uses it -- only reachable via
 * {@link ModuleWithAdditionalEntities}'s {@code additionalEntities()} attribute, exactly as it
 * would be for a real module whose {@code classesRoot} enumeration cannot see it.
 *
 * @since 6.3.0
 */
@Table("external_table")
public final class ExternalEntity {
}
