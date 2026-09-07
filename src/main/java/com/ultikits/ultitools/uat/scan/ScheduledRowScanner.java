package com.ultikits.ultitools.uat.scan;

import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.uat.ExtractorException;
import com.ultikits.ultitools.uat.RowId;
import com.ultikits.ultitools.utils.ReflectionUtil;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts {@code scheduled} surface rows from methods carrying {@code @Scheduled} (Phase 10,
 * D-10-04): one row per annotated method, with {@code delay}/{@code period} converted from ticks
 * to seconds — the raw tick values never appear in the row.
 * <p>
 * A {@code period} of {@code -1} (the annotation's own default, meaning "run once after delay")
 * is reported as a {@code one_shot} boolean rather than as a negative {@code period_seconds}: the
 * field is omitted entirely when one-shot, never emitted as a negative number.
 *
 * @since 6.3.0
 */
public final class ScheduledRowScanner {

    private static final String KIND_SCHEDULED = "scheduled";
    private static final int TICKS_PER_SECOND = 20;

    /**
     * Scans {@code classes} for {@code @Scheduled} methods and emits one row per method.
     *
     * @param origin  the module (or {@code "framework"}) these classes belong to
     * @param classes the loaded (uninitialized) classes to scan
     * @return every emitted row's field map, in scan order
     * @throws ExtractorException on a row-id collision between two distinct methods
     */
    public List<Map<String, Object>> scan(String origin, List<Class<?>> classes) throws ExtractorException {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, String> idOwners = new LinkedHashMap<>();
        for (Class<?> clazz : classes) {
            for (Method method : ReflectionUtil.getAllMethods(clazz)) {
                Scheduled scheduled = method.getAnnotation(Scheduled.class);
                if (scheduled == null) {
                    continue;
                }
                Map<String, Object> row = buildRow(origin, clazz, method, scheduled);
                claim(idOwners, String.valueOf(row.get("id")), clazz.getName(), method.getName());
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<String, Object> buildRow(String origin, Class<?> clazz, Method method, Scheduled scheduled) {
        String cls = clazz.getSimpleName();
        String member = method.getName();
        String id = RowId.of(KIND_SCHEDULED, origin, cls, member);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("kind", KIND_SCHEDULED);
        row.put("origin", origin);
        row.put("cls", cls);
        row.put("class", clazz.getName());
        row.put("member", member);
        row.put("delay_seconds", ticksToSeconds(scheduled.delay()));

        long period = scheduled.period();
        boolean oneShot = period < 0;
        row.put("one_shot", oneShot);
        if (!oneShot) {
            row.put("period_seconds", ticksToSeconds(period));
        }
        row.put("async", scheduled.async());
        return row;
    }

    private static Number ticksToSeconds(long ticks) {
        if (ticks % TICKS_PER_SECOND == 0) {
            return ticks / TICKS_PER_SECOND;
        }
        return ticks / (double) TICKS_PER_SECOND;
    }

    private static void claim(Map<String, String> idOwners, String id, String fullyQualifiedClassName, String member)
            throws ExtractorException {
        String descriptor = fullyQualifiedClassName + "#" + member;
        String existingOwner = idOwners.putIfAbsent(id, descriptor);
        if (existingOwner != null) {
            throw new ExtractorException("Row id collision " + id + " between " + existingOwner + " and " + descriptor);
        }
    }
}
