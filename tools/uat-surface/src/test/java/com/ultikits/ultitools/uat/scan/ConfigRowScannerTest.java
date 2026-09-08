package com.ultikits.ultitools.uat.scan;

import com.ultikits.ultitools.uat.ExtractorException;
import com.ultikits.ultitools.uat.fixtures.FixtureConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@link ConfigRowScanner} pairs every {@code @ConfigEntry} field with its enclosing
 * {@code @ConfigEntity}'s yml path and class, collects one {@code config_entities} entry per
 * distinct entity class, and fails closed on an orphaned entry (Phase 10, D-10-04/D-10-10).
 */
@DisplayName("ConfigRowScanner")
class ConfigRowScannerTest {

    @Test
    @DisplayName("emits one config row per @ConfigEntry field, joined to its owning @ConfigEntity")
    void emitsConfigRowsJoinedToEntity() throws ExtractorException {
        ConfigRowScanner.Result result = new ConfigRowScanner().scan("Fixture",
                Arrays.asList(FixtureConfig.Entity.class, FixtureConfig.OtherEntity.class));

        assertThat(result.getRows()).hasSize(3);
        assertThat(result.getRows()).extracting(row -> row.get("id")).doesNotHaveDuplicates();

        Map<String, Object> enabledRow = rowFor(result.getRows(), "enabled");
        assertThat(enabledRow.get("kind")).isEqualTo("config");
        assertThat(enabledRow.get("config_file")).isEqualTo("config/fixture.yml");
        assertThat(enabledRow.get("config_entity")).isEqualTo(FixtureConfig.Entity.class.getName());
        assertThat(enabledRow.get("path")).isEqualTo("fixture.enabled");
        assertThat(enabledRow.get("comment")).isEqualTo("Enable the fixture / 启用夹具");
        assertThat(enabledRow.get("field_type")).isEqualTo("boolean");

        Map<String, Object> nameRow = rowFor(result.getRows(), "name");
        assertThat(nameRow).doesNotContainKey("comment");

        assertThat(result.getEntities()).hasSize(2);
        assertThat(result.getEntities()).extracting(m -> m.get("id"))
                .isEqualTo(result.getEntities().stream().map(m -> m.get("id")).sorted().collect(java.util.stream.Collectors.toList()));

        Map<String, Object> fixtureEntity = entityFor(result.getEntities(), FixtureConfig.Entity.class.getName());
        assertThat(fixtureEntity.get("file")).isEqualTo("config/fixture.yml");
        assertThat(fixtureEntity.get("entry_count")).isEqualTo(2);

        Map<String, Object> otherEntity = entityFor(result.getEntities(), FixtureConfig.OtherEntity.class.getName());
        assertThat(otherEntity.get("entry_count")).isEqualTo(1);
    }

    @Test
    @DisplayName("a @ConfigEntity class with zero @ConfigEntry fields still appears in config_entities with entry_count 0")
    void emptyEntityStillAppearsInConfigEntities() throws ExtractorException {
        ConfigRowScanner.Result result = new ConfigRowScanner().scan("Fixture",
                Arrays.asList(FixtureConfig.EmptyEntity.class));

        assertThat(result.getRows()).isEmpty();
        assertThat(result.getEntities()).hasSize(1);
        Map<String, Object> emptyEntity = entityFor(result.getEntities(), FixtureConfig.EmptyEntity.class.getName());
        assertThat(emptyEntity.get("file")).isEqualTo("config/empty.yml");
        assertThat(emptyEntity.get("entry_count")).isEqualTo(0);
    }

    @Test
    @DisplayName("a @ConfigEntry field whose enclosing class has no @ConfigEntity fails closed naming the class and field")
    void orphanedConfigEntryFailsClosed() {
        assertThatThrownBy(() -> new ConfigRowScanner().scan("Fixture", Arrays.asList(FixtureConfig.Orphan.class)))
                .isInstanceOf(ExtractorException.class)
                .hasMessageContaining(FixtureConfig.Orphan.class.getName())
                .hasMessageContaining("value");
    }

    @Test
    @DisplayName("a @ConfigEntry with no explicit path resolves to the field's own name, matching AbstractConfigEntity's shorthand")
    void emptyPathResolvesToFieldName() throws ExtractorException {
        ConfigRowScanner.Result result = new ConfigRowScanner().scan("Fixture",
                Arrays.asList(FixtureConfig.ShorthandPathEntity.class));

        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).get("path")).isEqualTo("flag");
    }

    @Test
    @DisplayName("a subclass not redeclaring its own @ConfigEntity produces no row and no phantom entity for its inherited fields")
    void subclassNotRedeclaringConfigEntityProducesNothing() throws ExtractorException {
        ConfigRowScanner.Result result = new ConfigRowScanner().scan("Fixture",
                Arrays.asList(FixtureConfig.SubclassWithoutOwnConfigEntity.class));

        assertThat(result.getRows()).isEmpty();
        assertThat(result.getEntities()).isEmpty();
    }

    @Test
    @DisplayName("a default-parser field carries no parser key, but a custom @ConfigEntry(parser = ...) is captured (Codex review)")
    void customParserIsCapturedButDefaultParserIsNot() throws ExtractorException {
        ConfigRowScanner.Result defaultResult = new ConfigRowScanner().scan("Fixture",
                Arrays.asList(FixtureConfig.Entity.class));
        assertThat(rowFor(defaultResult.getRows(), "enabled")).doesNotContainKey("parser");

        ConfigRowScanner.Result customResult = new ConfigRowScanner().scan("Fixture",
                Arrays.asList(FixtureConfig.EntityWithCustomParser.class));
        Map<String, Object> customRow = rowFor(customResult.getRows(), "value");
        assertThat(customRow.get("parser")).isEqualTo(FixtureConfig.CustomParser.class.getName());
    }

    @Test
    @DisplayName("when the real entity is also present, a subclass not redeclaring @ConfigEntity does not duplicate or collide with it")
    void subclassAlongsideRealEntityDoesNotDuplicateRows() throws ExtractorException {
        ConfigRowScanner.Result result = new ConfigRowScanner().scan("Fixture",
                Arrays.asList(FixtureConfig.Entity.class, FixtureConfig.SubclassWithoutOwnConfigEntity.class));

        assertThat(result.getRows()).hasSize(2);
        assertThat(result.getEntities()).hasSize(1);
        assertThat(result.getEntities().get(0).get("class")).isEqualTo(FixtureConfig.Entity.class.getName());
    }

    private static Map<String, Object> rowFor(List<Map<String, Object>> rows, String member) {
        Optional<Map<String, Object>> match = rows.stream()
                .filter(row -> member.equals(row.get("member")))
                .findFirst();
        assertThat(match).as("row for member " + member).isPresent();
        return match.get();
    }

    private static Map<String, Object> entityFor(List<Map<String, Object>> entities, String className) {
        Optional<Map<String, Object>> match = entities.stream()
                .filter(entity -> className.equals(entity.get("class")))
                .findFirst();
        assertThat(match).as("entity for " + className).isPresent();
        return match.get();
    }
}
