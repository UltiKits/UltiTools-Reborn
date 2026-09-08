package com.ultikits.ultitools.uat;

import com.ultikits.ultitools.uat.fixtures.additionalentities.ExternalEntity;
import com.ultikits.ultitools.uat.fixtures.additionalentities.ModuleWithAdditionalEntities;
import com.ultikits.ultitools.uat.fixtures.additionalentities.ModuleWithOverlappingAdditionalEntity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code @UltiToolsModule.additionalEntities()} reaches the generated surface (Phase 10,
 * Codex review finding on PR #427): a class named only by that attribute -- never present in the
 * {@code classes} argument enumerated from a module's own {@code classesRoot} -- must still
 * produce a {@code persistence} row, because the runtime persistence layer scans it too (the
 * attribute is additive to, not a replacement for, the module's own jar scan; see its javadoc).
 */
@DisplayName("SurfaceAssembler @UltiToolsModule.additionalEntities() support")
class SurfaceAssemblerAdditionalEntitiesTest {

    @Test
    @DisplayName("a class named only by additionalEntities() still produces a persistence row")
    void additionalEntityProducesAPersistenceRowEvenWhenAbsentFromClasses() throws ExtractorException {
        // Deliberately excludes ExternalEntity.class -- the whole point is that the
        // module's own additionalEntities() attribute is what brings it in, not this list.
        List<Class<?>> classes = Collections.singletonList(ModuleWithAdditionalEntities.class);

        SurfaceAssembler.AssembledSurface surface = new SurfaceAssembler().assemble("Fixture", classes);

        boolean foundExternalEntityRow = surface.getRows().stream()
                .anyMatch(row -> "persistence".equals(row.get("kind"))
                        && ExternalEntity.class.getName().equals(row.get("class")));
        assertThat(foundExternalEntityRow)
                .as("expected a persistence row for %s, sourced only via additionalEntities()",
                        ExternalEntity.class.getName())
                .isTrue();
    }

    @Test
    @DisplayName("with no additionalEntities() declared, persistence scanning is unaffected")
    void noAdditionalEntitiesLeavesPersistenceScanningUnchanged() throws ExtractorException {
        List<Class<?>> classes = Collections.singletonList(ExternalEntity.class);

        SurfaceAssembler.AssembledSurface surface = new SurfaceAssembler().assemble("Fixture", classes);

        List<Map<String, Object>> persistenceRows = surface.getRows().stream()
                .filter(row -> "persistence".equals(row.get("kind")))
                .collect(java.util.stream.Collectors.toList());
        assertThat(persistenceRows).hasSize(1);
        assertThat(persistenceRows.get(0).get("class")).isEqualTo(ExternalEntity.class.getName());
    }

    @Test
    @DisplayName("a class named by additionalEntities() that is also already present in classesRoot is de-duplicated, not a collision")
    void overlappingAdditionalEntityIsDeduplicatedNotACollision() throws ExtractorException {
        List<Class<?>> classes = Arrays.asList(ModuleWithOverlappingAdditionalEntity.class, ExternalEntity.class);

        SurfaceAssembler.AssembledSurface surface = new SurfaceAssembler().assemble("Fixture", classes);

        List<Map<String, Object>> persistenceRows = surface.getRows().stream()
                .filter(row -> "persistence".equals(row.get("kind")))
                .collect(java.util.stream.Collectors.toList());
        assertThat(persistenceRows).hasSize(1);
        assertThat(persistenceRows.get(0).get("class")).isEqualTo(ExternalEntity.class.getName());
    }
}
