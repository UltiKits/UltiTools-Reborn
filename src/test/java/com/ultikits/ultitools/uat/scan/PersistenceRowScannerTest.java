package com.ultikits.ultitools.uat.scan;

import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.uat.ExtractorException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link PersistenceRowScanner} matches {@code PluginManager.resolveEntityClass}'s real
 * runtime discovery exactly (Phase 10, Codex review of PR #427): a subclass of a {@code @Table}
 * class that does not redeclare the annotation is never itself persisted, since {@code @Table}
 * carries no {@code @Inherited} meta-annotation and the runtime checks
 * {@code Class#isAnnotationPresent}, which does not walk the superclass hierarchy for a
 * non-inherited annotation.
 */
@DisplayName("PersistenceRowScanner")
class PersistenceRowScannerTest {

    @Test
    @DisplayName("a class carrying @Table directly produces one persistence row")
    void directTableAnnotationProducesARow() throws ExtractorException {
        List<Map<String, Object>> rows = new PersistenceRowScanner().scan("Fixture",
                Arrays.asList(DirectlyAnnotated.class));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("kind")).isEqualTo("persistence");
        assertThat(rows.get(0).get("table")).isEqualTo("direct_table");
    }

    @Test
    @DisplayName("a subclass that does not redeclare @Table produces no row, matching Class#isAnnotationPresent -- @Table is not @Inherited")
    void subclassNotRedeclaringTableProducesNoRow() throws ExtractorException {
        List<Map<String, Object>> rows = new PersistenceRowScanner().scan("Fixture",
                Arrays.asList(SubclassWithoutOwnTable.class));

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("a subclass that redeclares its own @Table still produces its own row")
    void subclassRedeclaringTableProducesItsOwnRow() throws ExtractorException {
        List<Map<String, Object>> rows = new PersistenceRowScanner().scan("Fixture",
                Arrays.asList(SubclassWithOwnTable.class));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("table")).isEqualTo("subclass_table");
    }

    @Table("direct_table")
    static class DirectlyAnnotated {
    }

    static class SubclassWithoutOwnTable extends DirectlyAnnotated {
        // Deliberately declares no @Table of its own -- the runtime's isAnnotationPresent
        // check never treats this as persisted, so neither should the extractor.
    }

    @Table("subclass_table")
    static class SubclassWithOwnTable extends DirectlyAnnotated {
    }
}
