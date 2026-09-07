package com.ultikits.ultitools.uat.scan;

import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import com.ultikits.ultitools.uat.ExtractorException;
import com.ultikits.ultitools.uat.RowId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts {@code persistence} surface rows from classes carrying {@code @Table} (Phase 10,
 * D-10-04): one row per persisted entity class, carrying the table name. Row identity is
 * {@code kind, origin, cls} — no member, matching {@code gen-registry.py}'s
 * {@code uid('persistence', origin, cls)} call site exactly (D-10-08).
 *
 * @since 6.3.0
 */
public final class PersistenceRowScanner {

    private static final String KIND_PERSISTENCE = "persistence";

    /**
     * Scans {@code classes} for {@code @Table} types and emits one row per class.
     *
     * @param origin  the module (or {@code "framework"}) these classes belong to
     * @param classes the loaded (uninitialized) classes to scan
     * @return every emitted row's field map, in scan order
     * @throws ExtractorException on a row-id collision between two distinct classes
     */
    public List<Map<String, Object>> scan(String origin, List<Class<?>> classes) throws ExtractorException {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, String> idOwners = new LinkedHashMap<>();
        for (Class<?> clazz : classes) {
            Table table = MergedAnnotationResolver.find(clazz, Table.class);
            if (table == null) {
                continue;
            }
            Map<String, Object> row = buildRow(origin, clazz, table);
            String existingOwner = idOwners.putIfAbsent(String.valueOf(row.get("id")), clazz.getName());
            if (existingOwner != null) {
                throw new ExtractorException("Row id collision " + row.get("id") + " between "
                        + existingOwner + " and " + clazz.getName());
            }
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> buildRow(String origin, Class<?> clazz, Table table) {
        String cls = clazz.getSimpleName();
        String id = RowId.of(KIND_PERSISTENCE, origin, cls);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("kind", KIND_PERSISTENCE);
        row.put("origin", origin);
        row.put("cls", cls);
        row.put("class", clazz.getName());
        row.put("table", table.value());
        return row;
    }
}
