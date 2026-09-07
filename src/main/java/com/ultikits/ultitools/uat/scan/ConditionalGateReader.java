package com.ultikits.ultitools.uat.scan;

import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import com.ultikits.ultitools.uat.RowId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code @ConditionalOnConfig} (Phase 10, D-10-04) and provides both of its surface
 * contributions: the {@code gate} object attached to every command/help/listener/scheduled row of
 * a gated class ({@link #collectGates}), and the standalone {@code conditional} row per gated
 * class ({@link #scanConditionalRows}) — because a gated class is not registered at all when the
 * key is off, so a tester who does not know the gate would record a false failure (UltiChat's
 * {@code ChannelCommands} is a real instance).
 * <p>
 * The row-id "condition text" input is built from the annotation's own resolved attribute values
 * in a fixed, whitespace-free order ({@code value=...,path=...,negate=...}), not from source
 * text: unlike {@code gen-registry.py}, which regex-captures the literal parenthesized source
 * (order- and formatting-dependent), this extractor reads compiled bytecode and has no source
 * text to capture. The result is deterministic and whitespace-free by construction, satisfying
 * D-10-08's "whitespace-stripped condition text" requirement without reproducing the old
 * registry's exact conditional-row ids (a recorded, accepted miss for this new-to-Java row kind).
 *
 * @since 6.3.0
 */
public final class ConditionalGateReader {

    private static final String KIND_CONDITIONAL = "conditional";

    /**
     * Builds the {@code gate} object for every gated class found in {@code classes}, keyed by
     * fully qualified class name for {@link com.ultikits.ultitools.uat.SurfaceAssembler} to
     * attach onto other rows.
     *
     * @param classes the loaded (uninitialized) classes to scan
     * @return gate objects keyed by fully qualified class name; empty if none are gated
     */
    public Map<String, Map<String, Object>> collectGates(List<Class<?>> classes) {
        Map<String, Map<String, Object>> gates = new LinkedHashMap<>();
        for (Class<?> clazz : classes) {
            ConditionalOnConfig annotation = MergedAnnotationResolver.find(clazz, ConditionalOnConfig.class);
            if (annotation != null) {
                gates.put(clazz.getName(), buildGate(annotation));
            }
        }
        return gates;
    }

    /**
     * Emits one {@code conditional} row per class carrying {@code @ConditionalOnConfig}.
     *
     * @param origin  the module (or {@code "framework"}) these classes belong to
     * @param classes the loaded (uninitialized) classes to scan
     * @return every emitted row's field map, in scan order
     */
    public List<Map<String, Object>> scanConditionalRows(String origin, List<Class<?>> classes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Class<?> clazz : classes) {
            ConditionalOnConfig annotation = MergedAnnotationResolver.find(clazz, ConditionalOnConfig.class);
            if (annotation == null) {
                continue;
            }
            Map<String, Object> gate = buildGate(annotation);
            String cls = clazz.getSimpleName();
            String id = RowId.of(KIND_CONDITIONAL, origin, cls, conditionText(gate));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("kind", KIND_CONDITIONAL);
            row.put("origin", origin);
            row.put("cls", cls);
            row.put("class", clazz.getName());
            row.put("gate", gate);
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> buildGate(ConditionalOnConfig annotation) {
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("value", annotation.value());
        gate.put("path", annotation.path());
        gate.put("negate", annotation.negate());
        return gate;
    }

    private static String conditionText(Map<String, Object> gate) {
        return "value=" + gate.get("value") + ",path=" + gate.get("path") + ",negate=" + gate.get("negate");
    }
}
