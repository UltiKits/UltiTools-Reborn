package com.ultikits.ultitools.uat.scan;

import com.ultikits.ultitools.annotations.EventListener;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import com.ultikits.ultitools.uat.ExtractorException;
import com.ultikits.ultitools.uat.RowId;
import com.ultikits.ultitools.utils.ReflectionUtil;

import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
 * <p>
 * Excludes two kinds of method Bukkit's own event registration will never actually invoke:
 * <ul>
 *     <li>one that does not take exactly one parameter, or whose parameter type is not
 *     assignable to {@link Event} — rejected or skipped by Bukkit's own registration entirely;</li>
 *     <li>a non-{@code public} {@code @EventHandler} method INHERITED from a superclass (not
 *     declared directly on the scanned class). Bukkit's listener discovery combines every
 *     {@code public} method reachable through the class hierarchy with every method (of any
 *     visibility) declared directly on the concrete listener class — so a {@code protected} or
 *     {@code private} handler declared directly on the class IS registered, but the same
 *     visibility inherited unchanged from an ancestor is not, because the ancestor's declaring
 *     class is never the concrete listener's own {@code getDeclaredMethods()} result.</li>
 * </ul>
 * Such methods can never actually be invoked as a handler, so emitting a normal row for either
 * would require a real-machine session to exercise a handler that does not exist at runtime.
 * <p>
 * The {@code event} field carries the parameter's FULLY QUALIFIED type name, not the simple
 * name: two distinct event classes in different packages sharing a simple name (a real,
 * Bukkit-legal situation across independently authored modules) are otherwise indistinguishable
 * to {@code tools/uat/uat.py}'s {@code next} command, which groups a batch's listener rows by
 * this exact field — a collision here would silently merge two unrelated triggers into one
 * dispatch group and tell the executor that firing either event adjudicates both.
 * <p>
 * Row ids stay byte-identical to the pre-existing {@code (kind, origin, cls, member)} scheme for
 * every non-overloaded handler — the overwhelming common case, and the only case any
 * historically recorded {@code ledger.json} entry can exist for, since a genuine overload
 * collision aborted extraction entirely before this fix and so could never have produced a
 * recorded verdict under either id. Only when two valid handler methods in the SAME class
 * legitimately share a name (overloaded by event parameter type, which Bukkit registers as two
 * independent handlers) does the id additionally fold in the event's simple name to disambiguate
 * — computed from a deterministic pre-scan of each class's own method-name multiset, not from
 * collision order, so the same source always produces the same ids regardless of scan order.
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
            List<Method> handlerMethods = new ArrayList<>();
            for (Method method : ReflectionUtil.getAllMethods(clazz)) {
                EventHandler handler = method.getAnnotation(EventHandler.class);
                if (handler == null || !isValidHandlerSignature(method) || !isRuntimeRegistrable(clazz, method)) {
                    continue;
                }
                handlerMethods.add(method);
            }
            Map<String, Integer> nameCounts = new LinkedHashMap<>();
            for (Method method : handlerMethods) {
                nameCounts.merge(method.getName(), 1, Integer::sum);
            }
            for (Method method : handlerMethods) {
                EventHandler handler = method.getAnnotation(EventHandler.class);
                boolean disambiguate = nameCounts.get(method.getName()) > 1;
                Map<String, Object> row = buildRow(origin, clazz, method, handler, annotation.manualRegister(), disambiguate);
                claim(idOwners, String.valueOf(row.get("id")), clazz.getName(), method.getName());
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * True when Bukkit's own {@code PluginManager.registerEvents} would actually register
     * {@code method} as a handler: exactly one parameter, whose type is assignable to
     * {@link Event}. A method failing either check is rejected or skipped by Bukkit itself,
     * never invoked no matter how the class is registered.
     */
    private static boolean isValidHandlerSignature(Method method) {
        Class<?>[] paramTypes = method.getParameterTypes();
        return paramTypes.length == 1 && Event.class.isAssignableFrom(paramTypes[0]);
    }

    /**
     * True when {@code method} is reachable by Bukkit's listener discovery on the concrete
     * {@code clazz}: either declared directly on {@code clazz} (any visibility), or {@code public}
     * (inherited public methods are reachable too). A non-public method inherited unchanged from
     * an ancestor is neither declared directly on {@code clazz} nor public, so it is never
     * registered for this concrete listener.
     */
    private static boolean isRuntimeRegistrable(Class<?> clazz, Method method) {
        return method.getDeclaringClass().equals(clazz) || Modifier.isPublic(method.getModifiers());
    }

    private static Map<String, Object> buildRow(String origin, Class<?> clazz, Method method, EventHandler handler,
            boolean manualRegister, boolean disambiguateId) {
        String cls = clazz.getSimpleName();
        String member = method.getName();
        Class<?>[] paramTypes = method.getParameterTypes();
        String eventFqcn = paramTypes.length > 0 ? paramTypes[0].getName() : null;
        String id = disambiguateId
                ? RowId.of(KIND_LISTENER, origin, cls, member, eventFqcn)
                : RowId.of(KIND_LISTENER, origin, cls, member);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("kind", KIND_LISTENER);
        row.put("origin", origin);
        row.put("cls", cls);
        row.put("class", clazz.getName());
        row.put("member", member);
        if (eventFqcn != null) {
            row.put("event", eventFqcn);
        }
        row.put("handler_priority", handler.priority().name());
        // ListenerManager.registerAll (both the plugin-module and external-plugin entry
        // points) deliberately skips automatic registration when @EventListener declares
        // manualRegister = true -- without recording that here, the surface and handover
        // make a manually-managed handler indistinguishable from an automatically
        // registered one, so an executor could fail it for never firing when its absence
        // from a listener dump is actually the expected, documented shape.
        row.put("manual_register", manualRegister);
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
