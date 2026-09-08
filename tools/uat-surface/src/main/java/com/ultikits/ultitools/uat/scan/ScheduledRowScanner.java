package com.ultikits.ultitools.uat.scan;

import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.uat.ExtractorException;
import com.ultikits.ultitools.uat.RowId;

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
 * A {@code period} of {@code <= 0} (the annotation's default is {@code -1}, meaning "run once
 * after delay") is reported as a {@code one_shot} boolean rather than as a {@code period_seconds}
 * value: the field is omitted entirely when one-shot, matching
 * {@code TaskManager.scanAndSchedule}'s own {@code period() <= 0} one-shot branch exactly (a
 * {@code period} of {@code 0}, not only a negative one, is one-shot at runtime).
 * <p>
 * Scans only {@link Class#getDeclaredMethods()}, not the inherited-method closure: the runtime
 * scheduler ({@code TaskManager.scanAndSchedule}) schedules only a class's own declared methods,
 * never one inherited unchanged from a superclass, so attributing an inherited {@code @Scheduled}
 * method to a subclass here would surface an execution row for a task that is never actually
 * registered under that subclass. Also excludes a method the runtime would skip with a logged
 * warning rather than schedule: one taking parameters, or not returning {@code void}/{@code Void}.
 *
 * @since 6.3.0
 */
public final class ScheduledRowScanner {

    private static final String KIND_SCHEDULED = "scheduled";
    private static final int TICKS_PER_SECOND = 20;

    /**
     * Scans {@code classes} for {@code @Scheduled} methods and emits one row per method the
     * runtime would actually schedule.
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
            for (Method method : clazz.getDeclaredMethods()) {
                Scheduled scheduled = method.getAnnotation(Scheduled.class);
                if (scheduled == null || !isRuntimeSchedulable(method)) {
                    continue;
                }
                Map<String, Object> row = buildRow(origin, clazz, method, scheduled);
                claim(idOwners, String.valueOf(row.get("id")), clazz.getName(), method.getName());
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * True when the runtime would actually register {@code method}, mirroring
     * {@code TaskManager.scanAndSchedule}'s own two checks exactly: a parameterized method, or
     * one not returning {@code void}/{@code Void}, is logged and skipped there, never scheduled.
     */
    private static boolean isRuntimeSchedulable(Method method) {
        if (method.getParameterCount() != 0) {
            return false;
        }
        Class<?> returnType = method.getReturnType();
        return returnType == void.class || returnType == Void.class;
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
        boolean oneShot = period <= 0;
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
