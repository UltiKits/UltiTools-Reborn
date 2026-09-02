package com.ultikits.ultitools.utils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GEN-07's observe-only classload filter audit (D-14): reproduces the exact matching semantics of
 * the three name-based filter layers {@link SecurityPolicy#isSafeClassName(String)} used to
 * enforce before D-12/D-13, and records what each removed layer WOULD have refused -- it refuses
 * nothing itself.
 * <p>
 * <b>Package-private on purpose.</b> This class holds the four lists {@code SecurityPolicy} used
 * to own directly (moved here once {@link SecurityPolicy#addTrustedPackage(String)} and
 * {@link SecurityPolicy#addDangerousClass(String)} became no-ops, so these now reflect exactly
 * what the framework as shipped would have judged, never a caller's runtime mutation). Same
 * package is the low-surface choice: the alternative would have been making the lists
 * {@code public} purely so a class outside {@code utils} could read them.
 * <p>
 * <b>This evaluator never decides.</b> {@link #classify(String)} returns which layer would have
 * refused a class name, or {@code null} for none -- never a boolean verdict, and nothing here can
 * be used by a caller to refuse a class. It exists to answer, after release, "did removing these
 * layers actually let anything through?" from real data rather than from an assertion.
 * <p>
 * <b>Cannot reuse {@code RemoteActionLog}.</b> {@code UltiTools.java}'s bootstrap runs the
 * classloading scan ({@code initPluginModules()}) before it constructs {@code RemoteActionLog}
 * ({@code initWebSocketManagers()}) -- the log does not exist yet at the point this evaluator
 * needs to run. Its {@code Verdict} is also {@code ALLOWED}/{@code DENIED}, and "would have been
 * denied under the old gate but is now unconditionally allowed" is neither. Logging follows the
 * same discipline {@code RemoteActionLog} and {@code ModuleScanDiagnostics} establish: a dedicated
 * non-root {@link Logger} with {@code setUseParentHandlers(false)}, so a record here can never
 * reach {@code SystemLogHandler} (which attaches only to the root logger and auto-reports any
 * {@link Level#SEVERE} record carrying a {@link Throwable} into {@code ErrorReportCollector}) and
 * risk the circular-logging hazard both of those classes' javadoc already warns about.
 * {@code setUseParentHandlers(false)} also disconnects this logger from the root logger's own
 * handlers -- the console/{@code logs/latest.log} sink the server installs there -- so, exactly
 * like {@code ModuleScanDiagnostics}, a dedicated {@link ConsoleHandler} is attached directly in
 * the static initializer below to restore that reach without restoring the
 * {@code SystemLogHandler} path. Internal failures of this evaluator itself would print to
 * {@link System#err} rather than through any {@link Logger} -- the same discipline, for the same
 * reason -- but this class has no internal failure mode: {@link #classify(String)} is a pure
 * function over in-memory {@link Set}s and never throws.
 * <p>
 * <b>Locale.ROOT, not the default locale.</b> The removed {@code isSafeClassName} lowercased with
 * the no-argument {@code String#toLowerCase()}, which uses the JVM's default locale. Under a
 * Turkish server locale, that method maps an ASCII capital {@code I} to a dotless {@code ı}, not a
 * dotted {@code i} -- silently changing which class names match a {@link #SUSPICIOUS_KEYWORDS}
 * entry depending on where the server happens to run. This evaluator lowercases with
 * {@link Locale#ROOT} instead, so a Turkish-locale server produces the same audit as an
 * English-locale one. An audit whose result depends on the server's locale is not a measurement,
 * so that latent defect is corrected here rather than carried into the telemetry meant to replace
 * the removed enforcement.
 *
 * @since 6.3.0
 */
final class ClassloadFilterAudit {

    private static final Logger AUDIT_LOGGER = Logger.getLogger(ClassloadFilterAudit.class.getName());

    /**
     * The level of this class's own {@link ConsoleHandler}. Named, package-private and asserted on
     * by {@code ClassloadFilterAuditTest} rather than left inline: the live handler list on a JUL
     * logger is global mutable state that other tests in the same JVM add to and remove from, so a
     * test that reads it back is order-dependent. This constant is the decision itself.
     */
    static final Level CONSOLE_HANDLER_LEVEL = Level.INFO;

    static {
        // Load-bearing (see class javadoc): the only way this logger's records could reach
        // SystemLogHandler is by propagating to the root logger, and this call removes that path
        // entirely.
        AUDIT_LOGGER.setUseParentHandlers(false);
        AUDIT_LOGGER.setLevel(Level.ALL);
        // setUseParentHandlers(false) above also disconnects this logger from the root logger's
        // own handlers -- the console/logs-latest.log sink the server installs there. A dedicated
        // handler restores that reach without restoring the SystemLogHandler path. Matches
        // ModuleScanDiagnostics's identical fix, confirmed on a real server in 07-JAPICMP-BASELINE.md's
        // "D-19 diagnostic observation" section -- found here by the same real-server verification
        // step this class's own D-14 mandates (Rule 1: a logger with no handler and parent
        // handlers disabled produces no output anywhere, silently).
        //
        // 07-fix: this handler sits at INFO, NOT ALL -- deliberately differing from
        // ModuleScanDiagnostics's otherwise identical block, because the two emit at completely
        // different volumes. ModuleScanDiagnostics logs FINE only for a class that actually failed
        // to load, which is rare. This class logs FINE for every class classify() returns a layer
        // for, and classify() returns WHITELIST for anything outside the seven trusted prefixes --
        // that is EVERY class of a third-party module, once per class, from the per-class scan
        // loops at PluginManager:474 and :622 (capped at 1000 classes per JAR by PluginManager:357).
        // At ALL that is up to a thousand console lines per module at startup, on top of the one
        // INFO summary that is the actual operator-facing output. The logger itself stays at ALL so
        // a test- or debug-attached handler still receives FINE.
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(CONSOLE_HANDLER_LEVEL);
        AUDIT_LOGGER.addHandler(consoleHandler);
    }

    // The four lists SecurityPolicy used to own directly, relocated here by D-14. Package-private
    // (no access modifier) so SecurityPolicy -- same package -- can still read them for
    // getSecurityPolicySummary() without either class needing a public accessor.
    static final Set<String> SYSTEM_DANGEROUS_CLASSES = new HashSet<>(Arrays.asList(
        "java.lang.ProcessBuilder",
        "java.lang.Runtime",
        "java.lang.System",
        "java.lang.reflect.Method",
        "java.io.FileOutputStream",
        "java.io.FileInputStream",
        "java.io.RandomAccessFile",
        "java.nio.file.Files",
        "java.nio.file.Paths",
        "javax.script.ScriptEngine",
        "javax.script.ScriptEngineManager",
        "sun.misc.Unsafe",
        "jdk.internal.misc.Unsafe",
        "java.net.Socket",
        "java.net.ServerSocket",
        "java.net.URL",
        "java.net.URLConnection",
        "java.security.AccessController",
        "java.lang.ClassLoader"
    ));

    static final Set<String> DANGEROUS_PACKAGE_PREFIXES = new HashSet<>(Arrays.asList(
        "java.lang.reflect",
        "java.security",
        "sun.misc",
        "jdk.internal",
        "com.sun",
        "javax.script",
        "java.rmi",
        "java.beans",
        "javax.management"
    ));

    static final Set<String> TRUSTED_PACKAGE_PREFIXES = new HashSet<>(Arrays.asList(
        "com.ultikits.ultitools",
        "com.ultikits.plugins",
        "org.bukkit",
        "net.md_5.bungee",
        "io.papermc.paper",
        "org.spigotmc",
        "net.kyori.adventure"
    ));

    static final Set<String> SUSPICIOUS_KEYWORDS = new HashSet<>(Arrays.asList(
        "process", "runtime", "script", "unsafe", "file", "network",
        "socket", "classloader", "reflection", "invoke", "exec",
        "shell", "cmd", "bash", "powershell", "system", "native"
    ));

    /** One accumulator entry per module currently being scanned; reset when its summary emits. */
    private static final Map<String, int[]> COUNTS_BY_MODULE = new ConcurrentHashMap<>();

    private ClassloadFilterAudit() {
    }

    /**
     * The four removed filter layers, in the exact order {@code isSafeClassName} evaluated them.
     */
    enum Layer {
        EXACT_BLACKLIST("would have been refused by the exact-name blacklist"),
        PACKAGE_PREFIX("would have been refused by the dangerous package-prefix list"),
        WHITELIST("would have been refused for not being in a trusted package"),
        KEYWORD("would have been refused by the suspicious-keyword list");

        private final String description;

        Layer(String description) {
            this.description = description;
        }

        String describe() {
            return description;
        }
    }

    /**
     * Classifies {@code className} against the four removed layers, in the exact order
     * {@code SecurityPolicy.isSafeClassName} evaluated them before D-12/D-13 (exact blacklist,
     * dangerous package prefix, trusted-package whitelist, suspicious keyword), and returns the
     * FIRST layer that would have refused it -- or {@code null} if none would have.
     * <p>
     * A {@code null} or blank {@code className} is not a filter layer and always classifies to
     * {@code null}: the original {@code isSafeClassName} null branch is now subsumed by
     * {@code ClassLoaderUtils}'s {@code VALID_CLASS_NAME_PATTERN} format regex, which is the only
     * thing that still rejects a malformed name (D-13).
     *
     * @param className the fully-qualified class name to classify, or {@code null}/blank
     * @return the first layer that would have refused {@code className}, or {@code null}
     */
    // PMD.NPathComplexity multiplies branch counts across the four sequential guard blocks
    // below. They do not nest and share no state: each is one layer of SecurityPolicy's
    // documented five-layer model, evaluated in the order the model defines, with an early
    // return. That order IS the semantics -- extracting each block into a helper would scatter
    // the one property a reader needs to check. NPath measures the wrong thing here.
    @SuppressWarnings("PMD.NPathComplexity")
    static Layer classify(String className) {
        if (className == null || className.trim().isEmpty()) {
            return null;
        }

        if (SYSTEM_DANGEROUS_CLASSES.contains(className)) {
            return Layer.EXACT_BLACKLIST;
        }

        for (String dangerousPrefix : DANGEROUS_PACKAGE_PREFIXES) {
            if (className.startsWith(dangerousPrefix)) {
                return Layer.PACKAGE_PREFIX;
            }
        }

        boolean trusted = false;
        for (String trustedPrefix : TRUSTED_PACKAGE_PREFIXES) {
            if (className.startsWith(trustedPrefix)) {
                trusted = true;
                break;
            }
        }
        if (!trusted) {
            return Layer.WHITELIST;
        }

        // Locale.ROOT, deliberately -- see class javadoc "Locale.ROOT, not the default locale".
        String lowerClassName = className.toLowerCase(Locale.ROOT);
        for (String keyword : SUSPICIOUS_KEYWORDS) {
            if (lowerClassName.contains(keyword)) {
                return Layer.KEYWORD;
            }
        }

        return null;
    }

    /**
     * Records one class evaluated during {@code moduleName}'s scan. A no-op for a {@code null} or
     * blank {@code moduleName}, or when {@link #classify(String)} finds no layer would have
     * refused {@code className} -- there is nothing interesting to record for a class none of the
     * four removed layers would have touched.
     * <p>
     * When a layer matches, increments that layer's per-module counter and logs the per-class
     * detail at {@link Level#FINE} -- matching {@code ModuleScanDiagnostics}'s
     * {@code recordSkippedClass}/{@code emitSummary} shape (D-19), so the two diagnostics read as
     * one pattern rather than two.
     * <p>
     * This method never decides anything: it has no boolean return, and {@code className} is
     * still handed to the real classloader by the caller regardless of what this method records
     * (Test 10).
     *
     * @param moduleName the module (or scan unit) currently being evaluated
     * @param className  the class name being evaluated, or {@code null}/blank
     */
    static void record(String moduleName, String className) {
        if (isBlank(moduleName)) {
            return;
        }
        Layer layer = classify(className);
        if (layer == null) {
            return;
        }
        int[] counts = COUNTS_BY_MODULE.computeIfAbsent(moduleName, key -> new int[Layer.values().length]);
        synchronized (counts) {
            counts[layer.ordinal()]++;
        }
        AUDIT_LOGGER.log(Level.FINE, "Module '" + moduleName + "': class '" + className + "' "
                + layer.describe() + " -- GEN-07 removed this layer, so it was allowed to load.");
    }

    /**
     * Emits exactly ONE {@link Level#INFO} summary for {@code moduleName}, ALWAYS -- even when
     * nothing was recorded for it. A module in which the removed layers would have refused
     * nothing is itself the measurement (Test 8), not silence; this differs deliberately from
     * {@code ModuleScanDiagnostics.emitSummary}, which is a no-op when its accumulator is empty.
     * Resets the accumulator for {@code moduleName} afterward, so a subsequent re-scan of the same
     * module starts clean.
     *
     * @param moduleName the module whose scan just finished
     */
    static void emitSummary(String moduleName) {
        if (isBlank(moduleName)) {
            return;
        }
        int[] counts = COUNTS_BY_MODULE.remove(moduleName);
        if (counts == null) {
            counts = new int[Layer.values().length];
        }
        int total = 0;
        for (int count : counts) {
            total += count;
        }
        String message = "Module '" + moduleName + "' classload-filter audit: " + total
                + " class(es) would have been refused by GEN-07's removed layers -- exact-name "
                + "blacklist: " + counts[Layer.EXACT_BLACKLIST.ordinal()] + ", dangerous package "
                + "prefix: " + counts[Layer.PACKAGE_PREFIX.ordinal()] + ", trusted-package "
                + "whitelist: " + counts[Layer.WHITELIST.ordinal()] + ", suspicious keyword: "
                + counts[Layer.KEYWORD.ordinal()] + ". These layers no longer refuse anything -- "
                + "this is telemetry only.";
        AUDIT_LOGGER.log(Level.INFO, message);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
