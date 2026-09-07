package com.ultikits.ultitools.uat;

import com.ultikits.ultitools.annotations.ComponentScan;
import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.annotations.EventListener;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.UsageLimit;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import com.ultikits.ultitools.uat.scan.CommandRowScanner;
import com.ultikits.ultitools.uat.scan.ConditionalGateReader;
import com.ultikits.ultitools.uat.scan.ConfigRowScanner;
import com.ultikits.ultitools.uat.scan.ListenerRowScanner;
import com.ultikits.ultitools.uat.scan.ModuleSwitchReader;
import com.ultikits.ultitools.uat.scan.PersistenceRowScanner;
import com.ultikits.ultitools.uat.scan.ScheduledRowScanner;

import java.lang.annotation.Annotation;
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
 * gates to the command/help/listener/scheduled rows of a gated class, collects the document-level
 * {@code config_entities} array and {@code registers_*} module switches, and — because a scanner
 * emitting one more row than expected is exactly the defect class this package exists to catch —
 * performs one final cross-scanner row-id collision check over the fully merged row list before
 * handing it to {@link CanonicalJsonWriter} (Phase 10 plan 10-02, Task 2).
 * <p>
 * {@link #COVERED_ANNOTATION_TYPES} is the extractor's own declared scope (Phase 10, D-10-04):
 * a hardcoded allowlist, not a directory listing, so that {@code AsyncCommand}, {@code RunAsync}
 * and {@code CmdSuggest} — deliberately excluded, see the field javadoc — never make a
 * directory-scan guard flap, and so a genuinely new criterion annotation without a scanner still
 * fails the guard instead of silently shipping uncovered.
 *
 * @since 6.3.0
 */
public final class SurfaceAssembler {

    /**
     * The extractor's complete, locked annotation scope (Phase 10, D-10-04, 13 types). Deliberately
     * excluded from this set, each for a stated reason (D-10-04's own discretion note):
     * <ul>
     *     <li>{@code @AsyncCommand} — describes how a command runs (thread), not what the
     *     surface is; the command row already exists independent of this attribute.</li>
     *     <li>{@code @RunAsync} — same reasoning as {@code @AsyncCommand}, the older sibling
     *     annotation with identical intent.</li>
     *     <li>{@code @CmdSuggest} — its tab-completion contribution is already folded into the
     *     command row's {@code params[].suggest} field via {@code @CmdParam}, so a dedicated
     *     scanner would duplicate information already on the row rather than add new surface.</li>
     * </ul>
     */
    public static final Set<Class<? extends Annotation>> COVERED_ANNOTATION_TYPES =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
                    CmdExecutor.class,
                    CmdMapping.class,
                    CmdParam.class,
                    CmdSender.class,
                    CmdCD.class,
                    CmdTarget.class,
                    UsageLimit.class,
                    ConfigEntity.class,
                    ConfigEntry.class,
                    EventListener.class,
                    Scheduled.class,
                    ConditionalOnConfig.class,
                    UltiToolsModule.class)));

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
     * @throws ExtractorException on any scanner's own failure, or a row-id collision across scanners
     */
    public AssembledSurface assemble(String origin, List<Class<?>> classes) throws ExtractorException {
        List<Map<String, Object>> rows = new ArrayList<>();

        // Read switches before scanning persistence: @UltiToolsModule.additionalEntities()
        // names @Table classes the module owns that live OUTSIDE classesRoot (a shared
        // library jar, a multi-module build's common artifact) — the runtime scans those
        // too (it is additive to, not a replacement for, the module's own JAR scan), so the
        // persistence scanner needs the union to match what actually gets persisted.
        ModuleSwitchReader.Switches switches = moduleSwitchReader.read(classes);
        List<Class<?>> persistenceClasses = classes;
        if (switches != null && !switches.getAdditionalEntities().isEmpty()) {
            // De-duplicated, not a plain concatenation: additionalEntities() naming a class
            // already present in classesRoot (or repeating the same class within its own
            // array) is accepted and de-duplicated at runtime (PluginManager
            // .scanPluginEntities's own HashSet), so passing the same Class<?> to
            // PersistenceRowScanner twice would trip its id-collision guard and abort
            // extraction for a module configuration the runtime accepts without complaint.
            Set<Class<?>> union = new LinkedHashSet<>(classes);
            union.addAll(switches.getAdditionalEntities());
            persistenceClasses = new ArrayList<>(union);
        }

        // ComponentScanner (both scanJar and scanDirectory) restricts registration to the
        // module's own configured scan packages -- @UltiToolsModule's scanBasePackages()/
        // scanBasePackageClasses(), merged through its @ComponentScan meta-annotation exactly
        // as PluginManager.getPluginScanPackages resolves it, defaulting to the entry class's
        // own package when neither is declared. An annotated command, listener, or
        // @ConditionalOnConfig class OUTSIDE those packages is never actually visited by
        // ComponentScanner.scanPackage at all -- its shouldRegister call (the runtime's sole
        // evaluator for the annotation) never runs for it (Codex review of PR #427) -- so
        // this SAME scoped class set backs command/listener extraction AND the standalone
        // conditional row / gate attachment below. Persistence/scheduled are deliberately
        // left unscoped: their own registration paths are not gated by this mechanism.
        // Config is scoped separately below, via its own DIFFERENT runtime derivation.
        List<Class<?>> componentScanClasses = filterByScanPackages(classes, deriveScanPackages(classes));

        for (SurfaceRow row : commandRowScanner.scan(origin, componentScanClasses)) {
            rows.add(row.toFieldMap());
        }
        rows.addAll(listenerRowScanner.scan(origin, componentScanClasses));
        rows.addAll(scheduledRowScanner.scan(origin, classes));
        rows.addAll(persistenceRowScanner.scan(origin, persistenceClasses));

        // UltiToolsPlugin.initConfig's package-scanned branch (config()/registersConfig true)
        // calls ConfigManager.registerAll per DependencyUtils.getPluginPackages -- a DIFFERENT
        // derivation from PluginManager.getPluginScanPackages: it additionally folds in
        // EnableAutoRegister.scanPackage(), a legacy single-string attribute @UltiToolsModule
        // does not alias onto anything (Codex review of PR #427). An @ConfigEntity outside
        // that scope is never loaded at runtime. When registersConfig is false, initConfig
        // takes its OTHER branch instead (getAllConfigs(), whatever the module's own override
        // returns) -- not package-scanned at all, and not statically resolvable without
        // initializing the class, so no restriction is applied in that case; scanning every
        // @ConfigEntity in `classes` is the closest safe approximation.
        //
        // Filtered with SEGMENT-AWARE matching, not filterByScanPackages' jar-parity prefix
        // test: PackageScanUtils.scanAnnotatedClasses (config's real runtime scan) delegates to
        // Guava's ClassPath#getTopLevelClassesRecursive, whose own contract is package-segment
        // precise -- "com.foo" matches "com.foo" and "com.foo.bar", never the sibling
        // "com.foobar" a raw string prefix would wrongly include (Codex review of PR #427).
        List<Class<?>> configClasses = filterByScanPackagesSegmentAware(classes, deriveConfigScanPackages(classes, switches));
        ConfigRowScanner.Result configResult = configRowScanner.scan(origin, configClasses);
        rows.addAll(configResult.getRows());

        rows.addAll(conditionalGateReader.scanConditionalRows(origin, componentScanClasses));

        attachGates(rows, conditionalGateReader.collectGates(componentScanClasses));

        detectCrossScannerCollisions(rows);

        Map<String, Object> documentExtras = new LinkedHashMap<>();
        documentExtras.put("config_entities", configResult.getEntities());

        if (switches != null) {
            documentExtras.put("registers_commands", switches.isRegistersCommands());
            documentExtras.put("registers_listeners", switches.isRegistersListeners());
            documentExtras.put("registers_config", switches.isRegistersConfig());
        }

        return new AssembledSurface(rows, documentExtras);
    }

    /**
     * Derives the module's runtime component-scan packages, mirroring
     * {@code PluginManager.getPluginScanPackages} exactly: merged {@code @ComponentScan}
     * resolution (which {@code @UltiToolsModule}'s {@code scanBasePackages()}/
     * {@code scanBasePackageClasses()} both alias onto via {@code @AliasFor}) contributes
     * every declared package, additively and in order, with duplicates collapsed to their
     * first occurrence; if none is declared, the entry class's own package is the sole
     * default, exactly as the runtime falls back.
     *
     * @param classes the loaded (uninitialized) classes to scan
     * @return the derived scan packages, or an EMPTY set when no {@code @UltiToolsModule}
     *         entry class is present among {@code classes} (e.g. a framework-origin scan) --
     *         an empty set is the "no restriction" sentinel {@link #filterByScanPackages}
     *         reads, since there is no per-module scan-package concept to apply at all
     */
    private static Set<String> deriveScanPackages(List<Class<?>> classes) {
        Class<?> entryClass = findModuleEntryClass(classes);
        if (entryClass == null) {
            return Collections.emptySet();
        }
        return finalizeScanPackages(collectComponentScanPackages(entryClass), entryClass);
    }

    /**
     * Derives the module's runtime CONFIG scan packages, mirroring
     * {@code DependencyUtils.getPluginPackages} exactly — a DIFFERENT derivation from
     * {@link #deriveScanPackages}, since {@code UltiToolsPlugin.initConfig}'s package-scanned
     * branch calls {@code ConfigManager.registerAll} per {@code DependencyUtils
     * .getPluginPackages}, not {@code PluginManager.getPluginScanPackages}: the same merged
     * {@code @ComponentScan} resolution, PLUS {@code EnableAutoRegister.scanPackage()} (a
     * legacy single-string attribute {@code @UltiToolsModule} does not alias onto anything).
     *
     * @param classes  the loaded (uninitialized) classes to scan
     * @param switches the module's registration switches (from {@link ModuleSwitchReader}),
     *                 or {@code null} if no {@code @UltiToolsModule} entry class is present
     * @return the derived scan packages, or an EMPTY set (no restriction) when
     *         {@code switches} is {@code null} or declares {@code registersConfig = false} --
     *         that branch calls the module's own {@code getAllConfigs()} instead, which is not
     *         package-scanned at all and not statically resolvable without initializing the
     *         class, so no restriction can be safely derived
     */
    private static Set<String> deriveConfigScanPackages(List<Class<?>> classes, ModuleSwitchReader.Switches switches) {
        if (switches == null || !switches.isRegistersConfig()) {
            return Collections.emptySet();
        }
        Class<?> entryClass = findModuleEntryClass(classes);
        if (entryClass == null) {
            return Collections.emptySet();
        }
        LinkedHashSet<String> scanPackages = collectComponentScanPackages(entryClass);
        EnableAutoRegister enableAutoRegister = MergedAnnotationResolver.find(entryClass, EnableAutoRegister.class);
        if (enableAutoRegister != null && !enableAutoRegister.scanPackage().isEmpty()) {
            scanPackages.add(enableAutoRegister.scanPackage());
        }
        return finalizeScanPackages(scanPackages, entryClass);
    }

    /**
     * Finds the {@code @UltiToolsModule} entry class among {@code classes}, the same way
     * {@code ModuleSwitchReader.read} does.
     *
     * @param classes the loaded (uninitialized) classes to scan
     * @return the entry class, or {@code null} if none carries {@code @UltiToolsModule}
     */
    private static Class<?> findModuleEntryClass(List<Class<?>> classes) {
        for (Class<?> clazz : classes) {
            if (MergedAnnotationResolver.find(clazz, UltiToolsModule.class) != null) {
                return clazz;
            }
        }
        return null;
    }

    /**
     * Accumulates the packages a merged {@code @ComponentScan} on {@code entryClass}
     * contributes: {@code value()}, {@code basePackages()}, and the packages of every
     * {@code basePackageClasses()} marker, additively and in declaration order.
     */
    private static LinkedHashSet<String> collectComponentScanPackages(Class<?> entryClass) {
        LinkedHashSet<String> scanPackages = new LinkedHashSet<>();
        ComponentScan merged = MergedAnnotationResolver.find(entryClass, ComponentScan.class);
        if (merged != null) {
            Collections.addAll(scanPackages, merged.value());
            Collections.addAll(scanPackages, merged.basePackages());
            for (Class<?> markerClass : merged.basePackageClasses()) {
                Package markerPackage = markerClass.getPackage();
                if (markerPackage != null) {
                    scanPackages.add(markerPackage.getName());
                }
            }
        }
        return scanPackages;
    }

    /**
     * Falls back to {@code entryClass}'s own package when {@code scanPackages} accumulated
     * nothing, matching both {@code PluginManager.getPluginScanPackages} and
     * {@code DependencyUtils.getPluginPackages}'s identical default-to-own-package behavior.
     */
    private static Set<String> finalizeScanPackages(LinkedHashSet<String> scanPackages, Class<?> entryClass) {
        if (scanPackages.isEmpty()) {
            Package entryPackage = entryClass.getPackage();
            if (entryPackage != null) {
                scanPackages.add(entryPackage.getName());
            }
        }
        return scanPackages;
    }

    /**
     * Restricts {@code classes} to those whose fully qualified name falls under one of
     * {@code scanPackages}, matching {@code ComponentScanner.scanJar}'s own prefix test
     * ({@code entryName.startsWith(packagePath)}) exactly, sibling-package imprecision
     * included: parity with the real (occasionally over-broad) runtime scan is the goal here,
     * not a stricter replacement for it.
     *
     * @param classes      the loaded (uninitialized) classes to scan
     * @param scanPackages the packages to restrict to; an EMPTY set means no restriction
     *                     applies (no {@code @UltiToolsModule} entry class was found)
     * @return {@code classes} unchanged when {@code scanPackages} is empty, otherwise only
     *         the classes whose name falls under one of {@code scanPackages}
     */
    private static List<Class<?>> filterByScanPackages(List<Class<?>> classes, Set<String> scanPackages) {
        if (scanPackages.isEmpty()) {
            return classes;
        }
        List<Class<?>> filtered = new ArrayList<>();
        for (Class<?> clazz : classes) {
            String className = clazz.getName();
            for (String scanPackage : scanPackages) {
                if (className.startsWith(scanPackage)) {
                    filtered.add(clazz);
                    break;
                }
            }
        }
        return filtered;
    }

    /**
     * Restricts {@code classes} to those whose fully qualified name falls under one of
     * {@code scanPackages}, using PACKAGE-SEGMENT-aware matching: a class's name must equal a
     * scan package exactly, or start with it followed by a {@code '.'} — never merely share a
     * string prefix. Matches {@code PackageScanUtils.scanAnnotatedClasses}'s own delegation to
     * Guava's {@code ClassPath#getTopLevelClassesRecursive}, whose documented contract is
     * segment-precise: querying {@code "com.foo"} never matches the sibling package
     * {@code "com.foobar"}, unlike {@link #filterByScanPackages}'s deliberately jar-scan-shaped
     * raw prefix test.
     *
     * @param classes      the loaded (uninitialized) classes to scan
     * @param scanPackages the packages to restrict to; an EMPTY set means no restriction applies
     * @return {@code classes} unchanged when {@code scanPackages} is empty, otherwise only the
     *         classes whose name falls under one of {@code scanPackages} by package segment
     */
    private static List<Class<?>> filterByScanPackagesSegmentAware(List<Class<?>> classes, Set<String> scanPackages) {
        if (scanPackages.isEmpty()) {
            return classes;
        }
        List<Class<?>> filtered = new ArrayList<>();
        for (Class<?> clazz : classes) {
            Package classPackage = clazz.getPackage();
            String packageName = classPackage != null ? classPackage.getName() : "";
            for (String scanPackage : scanPackages) {
                if (packageName.equals(scanPackage) || packageName.startsWith(scanPackage + ".")) {
                    filtered.add(clazz);
                    break;
                }
            }
        }
        return filtered;
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

    /**
     * The final, whole-document collision check (Phase 10 plan 10-02, Task 2): no individual
     * scanner can see another scanner's ids, so this is the only place a collision across two
     * DIFFERENT row kinds — or a same-simple-name pair a single scanner's own internal check
     * already caught, re-confirmed here — is guaranteed to surface, naming both fully qualified
     * classes (and their members, when known) rather than silently keeping only one row.
     */
    private static void detectCrossScannerCollisions(List<Map<String, Object>> rows) throws ExtractorException {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.get("id"));
            // Map.putIfAbsent returns non-null only when `id` was already claimed by a
            // DIFFERENT prior row object -- each row in `rows` is a distinct instance
            // created exactly once by its own scanner, so `existing` can never be the
            // literal same reference as `row`. A reference-identity self-check here was
            // therefore always vacuously true whenever `existing != null`; removed rather
            // than rewritten to Map.equals(), which would incorrectly treat two distinct
            // rows that happen to carry byte-identical field content as "not a collision".
            Map<String, Object> existing = byId.putIfAbsent(id, row);
            if (existing != null) {
                throw new ExtractorException("Row id collision " + id + " between "
                        + descriptorOf(existing) + " and " + descriptorOf(row));
            }
        }
    }

    private static String descriptorOf(Map<String, Object> row) {
        Object className = row.get("class");
        Object member = row.get("member");
        if (member != null) {
            return className + "#" + member;
        }
        return String.valueOf(className);
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
