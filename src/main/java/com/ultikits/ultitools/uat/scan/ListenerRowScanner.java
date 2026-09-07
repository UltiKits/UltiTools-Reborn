package com.ultikits.ultitools.uat.scan;

import com.ultikits.ultitools.annotations.EventListener;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import com.ultikits.ultitools.uat.ExtractorException;
import com.ultikits.ultitools.uat.RowId;
import com.ultikits.ultitools.utils.ReflectionUtil;

import org.bukkit.event.EventHandler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts {@code listener} surface rows from loaded {@code @EventListener} classes (Phase 10,
 * D-10-04, D-10-05): one row per Bukkit {@code @EventHandler} method, not one row per class —
 * the row unit is the handler method, matching {@code gen-registry.py}'s existing {@code LIS-}
 * rows and the UltiChat control audit (4 {@code @EventListener} classes carrying 6 handler
 * methods).
 * <p>
 * A class carrying {@code @EventListener} is scanned regardless of its {@code manualRegister}
 * value — a suppressed registration is still a fact to verify, per D-10-04's discretion note on
 * {@code manualRegister}-true executors, applied the same way here.
 *
 * @since 6.3.0
 */
public final class ListenerRowScanner {

    private static final String KIND_LISTENER = "listener";

    /**
     * Scans {@code classes} for {@code @EventListener} types and emits one row per
     * {@code @EventHandler} method.
     *
     * @param origin  the module (or {@code "framework"}) these classes belong to
     * @param classes the loaded (uninitialized) classes to scan
     * @return every emitted row's field map, in scan order
     * @throws ExtractorException on a row-id collision between two distinct handler methods
     */
    public List<Map<String, Object>> scan(String origin, List<Class<?>> classes) throws ExtractorException {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, String> idOwners = new LinkedHashMap<>();
        for (Class<?> clazz : classes) {
            EventListener annotation = MergedAnnotationResolver.find(clazz, EventListener.class);
            if (annotation == null) {
                continue;
            }
            for (Method method : ReflectionUtil.getAllMethods(clazz)) {
                EventHandler handler = method.getAnnotation(EventHandler.class);
                if (handler == null) {
                    continue;
                }
                Map<String, Object> row = buildRow(origin, clazz, method, handler);
                claim(idOwners, String.valueOf(row.get("id")), clazz.getName(), method.getName());
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<String, Object> buildRow(String origin, Class<?> clazz, Method method, EventHandler handler) {
        String cls = clazz.getSimpleName();
        String member = method.getName();
        String id = RowId.of(KIND_LISTENER, origin, cls, member);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("kind", KIND_LISTENER);
        row.put("origin", origin);
        row.put("cls", cls);
        row.put("class", clazz.getName());
        row.put("member", member);
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length > 0) {
            row.put("event", paramTypes[0].getSimpleName());
        }
        row.put("handler_priority", handler.priority().name());
        return row;
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
