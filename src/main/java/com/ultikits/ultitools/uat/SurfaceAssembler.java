package com.ultikits.ultitools.uat;

import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.uat.scan.CommandRowScanner;
import com.ultikits.ultitools.uat.scan.ConditionalGateReader;
import com.ultikits.ultitools.uat.scan.ConfigRowScanner;
import com.ultikits.ultitools.uat.scan.ListenerRowScanner;
import com.ultikits.ultitools.uat.scan.ModuleSwitchReader;
import com.ultikits.ultitools.uat.scan.PersistenceRowScanner;
import com.ultikits.ultitools.uat.scan.ScheduledRowScanner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single place every scanner's output converges (Phase 10, D-10-04/plan 10-02 Task 1): runs
 * every row-kind scanner over one module's loaded classes, attaches {@link ConditionalOnConfig}
 * gates to the command/help/listener/scheduled rows of a gated class, and collects the
 * document-level {@code config_entities} array and {@code registers_*} module switches before
 * handing the merged rows to {@link CanonicalJsonWriter}.
 *
 * @since 6.3.0
 */
public final class SurfaceAssembler {

    private static final Set<String> GATE_ELIGIBLE_KINDS =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("command", "help", "listener", "scheduled")));

    private final CommandRowScanner commandRowScanner = new CommandRowScanner();
    private final ListenerRowScanner listenerRowScanner = new ListenerRowScanner();
    private final ScheduledRowScanner scheduledRowScanner = new ScheduledRowScanner();
    private final PersistenceRowScanner persistenceRowScanner = new PersistenceRowScanner();
    private final ConfigRowScanner configRowScanner = new ConfigRowScanner();
    private final ConditionalGateReader conditionalGateReader = new ConditionalGateReader();
    private final ModuleSwitchReader moduleSwitchReader = new ModuleSwitchReader();

    /**
     * Runs every row-kind scanner over {@code classes} and assembles the full document.
     *
     * @param origin  the module (or {@code "framework"}) these classes belong to
     * @param classes the loaded (uninitialized) classes to scan
     * @return the merged rows plus document-level extras ({@code config_entities}, {@code registers_*})
     * @throws ExtractorException on any scanner's own failure
     */
    public AssembledSurface assemble(String origin, List<Class<?>> classes) throws ExtractorException {
        List<Map<String, Object>> rows = new ArrayList<>();

        for (SurfaceRow row : commandRowScanner.scan(origin, classes)) {
            rows.add(row.toFieldMap());
        }
        rows.addAll(listenerRowScanner.scan(origin, classes));
        rows.addAll(scheduledRowScanner.scan(origin, classes));
        rows.addAll(persistenceRowScanner.scan(origin, classes));

        ConfigRowScanner.Result configResult = configRowScanner.scan(origin, classes);
        rows.addAll(configResult.getRows());

        rows.addAll(conditionalGateReader.scanConditionalRows(origin, classes));

        attachGates(rows, conditionalGateReader.collectGates(classes));

        Map<String, Object> documentExtras = new LinkedHashMap<>();
        documentExtras.put("config_entities", configResult.getEntities());

        ModuleSwitchReader.Switches switches = moduleSwitchReader.read(classes);
        if (switches != null) {
            documentExtras.put("registers_commands", switches.isRegistersCommands());
            documentExtras.put("registers_listeners", switches.isRegistersListeners());
            documentExtras.put("registers_config", switches.isRegistersConfig());
        }

        return new AssembledSurface(rows, documentExtras);
    }

    private static void attachGates(List<Map<String, Object>> rows, Map<String, Map<String, Object>> gatesByClassName) {
        if (gatesByClassName.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : rows) {
            Object kind = row.get("kind");
            if (!(kind instanceof String) || !GATE_ELIGIBLE_KINDS.contains(kind)) {
                continue;
            }
            Object className = row.get("class");
            Map<String, Object> gate = gatesByClassName.get(className);
            if (gate != null) {
                row.put("gate", gate);
            }
        }
    }

    /** The merged row set plus document-level extras, ready for {@link CanonicalJsonWriter}. */
    public static final class AssembledSurface {
        private final List<Map<String, Object>> rows;
        private final Map<String, Object> documentExtras;

        AssembledSurface(List<Map<String, Object>> rows, Map<String, Object> documentExtras) {
            this.rows = rows;
            this.documentExtras = documentExtras;
        }

        public List<Map<String, Object>> getRows() {
            return rows;
        }

        public Map<String, Object> getDocumentExtras() {
            return documentExtras;
        }
    }
}
