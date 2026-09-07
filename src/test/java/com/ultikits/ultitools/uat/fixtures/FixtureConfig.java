package com.ultikits.ultitools.uat.fixtures;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * Fixture {@code @ConfigEntity}/{@code @ConfigEntry} classes for {@code ConfigRowScannerTest}
 * (Phase 10 plan 10-02, Task 1).
 * <p>
 * {@link #Entity} carries two entries, proving the field-to-entity join
 * ({@code config_file}/{@code config_entity}) and the {@code config_entities} entry_count.
 * {@link #OtherEntity} shares no fields with {@link #Entity} but is a second distinct entity for
 * asserting the {@code config_entities} array holds one entry per class, sorted by id.
 * {@link #Orphan} carries an {@code @ConfigEntry} field on a class with no {@code @ConfigEntity}
 * at all -- the specimen for the "no enclosing entity" error path.
 * <p>
 * Reflection-only scanning never constructs these classes, so none needs to extend
 * {@code AbstractConfigEntity} or provide its required constructor.
 *
 * @since 6.3.0
 */
// This class is a namespace for the nested @ConfigEntity fixtures below, not a utility class
// with static helpers of its own -- the private constructor exists only to block a pointless
// `new FixtureConfig()`. PMD's rule assumes a non-instantiatable class with no static members
// serves no purpose; the purpose here is holding the nested classes.
@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
public final class FixtureConfig {

    private FixtureConfig() {
    }

    @ConfigEntity("config/fixture.yml")
    public static class Entity {
        @ConfigEntry(path = "fixture.enabled", comment = "Enable the fixture / 启用夹具")
        private boolean enabled = true;

        @ConfigEntry(path = "fixture.name")
        private String name = "default";
    }

    @ConfigEntity("config/other.yml")
    public static class OtherEntity {
        @ConfigEntry(path = "other.count")
        private int count = 0;
    }

    // Deliberately carries no @ConfigEntry field at all -- the specimen for the "still
    // appears in config_entities with entry_count 0" behaviour (Codex review of PR #427):
    // a class this shape is invisible to check_matrix.py's uncovered-entity gap detection
    // unless it gets a config_entities entry regardless of having zero entries.
    @ConfigEntity("config/empty.yml")
    public static class EmptyEntity {
    }

    public static class Orphan {
        @ConfigEntry(path = "orphan.value")
        private String value = "orphan";
    }
}
