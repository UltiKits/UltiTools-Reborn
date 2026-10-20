package com.ultikits.ultitools.abstracts.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;

/**
 * Pins {@link BaseDataEntity}'s ownership of its own {@code id} field and the JSON round-trip
 * behaviour that ownership must preserve.
 * <p>
 * Written RED-first (07-13-PLAN.md's expanded scope, GEN-04): before this task, {@code id} is
 * declared on the now-deleted {@code AbstractDataEntity}, so {@link
 * #idFieldIsDeclaredDirectlyOnBaseDataEntity()} fails with {@code NoSuchFieldException}. The two
 * round-trip tests already pass against the old two-class shape (an {@code Object}-typed field);
 * they stay in this file as a permanent regression pin proving the id-type change from {@code
 * Object} to {@code ID extends Serializable} does not alter what gets persisted or read back for
 * the JSON storage backend, which is the concrete risk 07-13-PLAN.md's hazard list names.
 * <p>
 * 钉住 {@link BaseDataEntity} 对自身 {@code id} 字段的直接持有，以及这一变更必须维持的
 * JSON 往返行为。
 *
 * @since 6.3.0
 */
class BaseDataEntityTest {

    private static final Gson GSON = new Gson();

    @Test
    @DisplayName("BaseDataEntity declares its own id field (not inherited from AbstractDataEntity)")
    void idFieldIsDeclaredDirectlyOnBaseDataEntity() throws NoSuchFieldException {
        Field idField = BaseDataEntity.class.getDeclaredField("id");

        assertThat(idField.getAnnotation(Column.class)).isNotNull();
        assertThat(idField.getAnnotation(Column.class).value()).isEqualTo("id");
    }

    @Test
    @DisplayName("A round-tripped String id survives GSON toJson/fromJson unchanged")
    void jsonRoundTripPreservesStringId() {
        TestEntity original = new TestEntity();
        original.setId("entity-abc-123");
        original.setName("round-trip");

        String json = GSON.toJson(original);
        TestEntity restored = GSON.fromJson(json, TestEntity.class);

        assertThat(restored.getId()).isInstanceOf(String.class);
        assertThat(restored.getId()).isEqualTo("entity-abc-123");
    }

    @Test
    @DisplayName("A legacy on-disk JSON string id deserializes as a String, not a Double or other type")
    void legacyPersistedJsonIdDeserializesAsString() {
        // Mimics a file already on disk under the json storage backend, written before this
        // change, when AbstractDataEntity's id field was declared Object -- Gson serializes an
        // Object-typed String field to a plain JSON string either way, so this fixture is
        // representative of what a real pre-6.3.0 entity file looks like on disk.
        String legacyJson = "{\"id\":\"legacy-uuid-9f8e\",\"name\":\"pre-existing\"}";

        TestEntity restored = GSON.fromJson(legacyJson, TestEntity.class);

        assertThat(restored.getId()).isInstanceOf(String.class);
        assertThat(restored.getId()).isEqualTo("legacy-uuid-9f8e");
    }

    @Table("test_entity")
    private static class TestEntity extends BaseDataEntity<String> {
        @Column("name")
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
