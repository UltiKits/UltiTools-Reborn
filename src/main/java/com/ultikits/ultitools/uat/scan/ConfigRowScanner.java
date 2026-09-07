package com.ultikits.ultitools.uat.scan;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import com.ultikits.ultitools.uat.ExtractorException;
import com.ultikits.ultitools.uat.RowId;
import com.ultikits.ultitools.utils.ReflectionUtil;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts {@code config} surface rows from {@code @ConfigEntry} fields (Phase 10, D-10-04) and
 * collects the document-level {@code config_entities} array (D-10-10).
 * <p>
 * Every {@code @ConfigEntry} field pairs with its enclosing class's {@code @ConfigEntity} value
 * (the yml path), emitted as {@code config_file}, and with the enclosing class's fully qualified
 * name, emitted as {@code config_entity} — the join key {@code plan 10-04}'s checker (and every
 * later per-entity assertion file) uses to group fields into entities without re-reading module
 * source. A {@code @ConfigEntry} field whose enclosing class carries no {@code @ConfigEntity} is
 * an {@link ExtractorException} naming the class and field, never a row with a null path.
 * <p>
 * {@code config_entities} is keyed by the entity class's fully qualified name, not by its yml
 * path: two {@code @ConfigEntity} classes can legitimately name the same file, and the class is
 * part of the entity id's input for exactly that reason.
 *
 * @since 6.3.0
 */
public final class ConfigRowScanner {

    private static final String KIND_CONFIG = "config";
    private static final String KIND_CONFIG_ENTITY = "config_entity";

    /**
     * Scans {@code classes} for {@code @ConfigEntry} fields and emits one {@code config} row per
     * field, plus one {@code config_entities} entry per distinct {@code @ConfigEntity} class.
     *
     * @param origin  the module (or {@code "framework"}) these classes belong to
     * @param classes the loaded (uninitialized) classes to scan
     * @return the config rows and the sorted {@code config_entities} array
     * @throws ExtractorException on an orphaned {@code @ConfigEntry}, or a row-id collision
     */
    public Result scan(String origin, List<Class<?>> classes) throws ExtractorException {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, String> idOwners = new LinkedHashMap<>();
        Map<String, EntityAccumulator> entitiesByClassName = new LinkedHashMap<>();

        // Pre-seed every @ConfigEntity class BEFORE walking fields, so a class annotated
        // @ConfigEntity but declaring zero @ConfigEntry fields still gets a config_entities
        // entry (entry_count 0). Without this, such a class was silently absent from the
        // surface entirely -- invisible to check_matrix.py's uncovered-entity gap detection,
        // which can only flag an entity it can see in the first place.
        for (Class<?> clazz : classes) {
            ConfigEntity entityOnClass = MergedAnnotationResolver.find(clazz, ConfigEntity.class);
            if (entityOnClass != null) {
                entitiesByClassName.computeIfAbsent(clazz.getName(),
                        k -> new EntityAccumulator(clazz, entityOnClass.value(), origin));
            }
        }

        for (Class<?> clazz : classes) {
            for (Field field : ReflectionUtil.getAllFields(clazz)) {
                ConfigEntry entry = field.getAnnotation(ConfigEntry.class);
                if (entry == null) {
                    continue;
                }
                ConfigEntity entity = MergedAnnotationResolver.find(clazz, ConfigEntity.class);
                if (entity == null) {
                    throw new ExtractorException("@ConfigEntry on " + clazz.getName() + "#" + field.getName()
                            + " has no enclosing @ConfigEntity");
                }

                Map<String, Object> row = buildRow(origin, clazz, field, entry, entity);
                String id = String.valueOf(row.get("id"));
                String descriptor = clazz.getName() + "#" + field.getName();
                String existingOwner = idOwners.putIfAbsent(id, descriptor);
                if (existingOwner != null) {
                    throw new ExtractorException("Row id collision " + id + " between " + existingOwner
                            + " and " + descriptor);
                }
                rows.add(row);

                entitiesByClassName
                        .computeIfAbsent(clazz.getName(), k -> new EntityAccumulator(clazz, entity.value(), origin))
                        .entryCount++;
            }
        }

        List<Map<String, Object>> entities = new ArrayList<>();
        for (EntityAccumulator accumulator : entitiesByClassName.values()) {
            entities.add(accumulator.toFieldMap());
        }
        entities.sort(Comparator.comparing(m -> String.valueOf(m.get("id"))));

        return new Result(rows, entities);
    }

    private static Map<String, Object> buildRow(String origin, Class<?> clazz, Field field, ConfigEntry entry,
            ConfigEntity entity) {
        String cls = clazz.getSimpleName();
        String member = field.getName();
        String id = RowId.of(KIND_CONFIG, origin, cls, member);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("kind", KIND_CONFIG);
        row.put("origin", origin);
        row.put("cls", cls);
        row.put("class", clazz.getName());
        row.put("member", member);
        row.put("config_file", entity.value());
        row.put("config_entity", clazz.getName());
        row.put("path", entry.path());
        if (!entry.comment().isEmpty()) {
            row.put("comment", entry.comment());
        }
        row.put("field_type", field.getType().getSimpleName());
        return row;
    }

    /** The config rows plus the sorted {@code config_entities} array. */
    public static final class Result {
        private final List<Map<String, Object>> rows;
        private final List<Map<String, Object>> entities;

        Result(List<Map<String, Object>> rows, List<Map<String, Object>> entities) {
            this.rows = rows;
            this.entities = entities;
        }

        public List<Map<String, Object>> getRows() {
            return rows;
        }

        public List<Map<String, Object>> getEntities() {
            return entities;
        }
    }

    private static final class EntityAccumulator {
        private final Class<?> declaringClass;
        private final String file;
        private final String origin;
        private int entryCount;

        EntityAccumulator(Class<?> declaringClass, String file, String origin) {
            this.declaringClass = declaringClass;
            this.file = file;
            this.origin = origin;
        }

        Map<String, Object> toFieldMap() {
            String id = RowId.of(KIND_CONFIG_ENTITY, origin, declaringClass.getName());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("class", declaringClass.getName());
            map.put("file", file);
            map.put("entry_count", entryCount);
            return map;
        }
    }
}
