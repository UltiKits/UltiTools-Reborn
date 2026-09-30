package com.ultikits.ultitools.utils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * denied under the old gate but is now unconditionally allowed" is neither.
 * <p>
 * <b>Logging (#557).</b> Records go to a dedicated non-root {@link Logger} with
 * {@code setUseParentHandlers(false)}, so a record here never propagates on its own to the root
 * logger that {@code SystemLogHandler} watches (that handler auto-reports any SEVERE record carrying
 * a {@link Throwable} into {@code ErrorReportCollector}). A {@link PluginLoggerBridge} forwards INFO
 * and above to the plugin's own logger, so an operator sees the line at its real level and in the
 * server's normal log format. An earlier private console handler wrote to the standard error stream,
 * which Paper prints as two WARN lines per record. What an operator sees, by design:
 * <ul>
 *   <li>a clean result (no class would have been refused) is logged at FINE and so is not shown -- the
 *       class-name filters no longer exist, so "nothing would have been refused" is not news;</li>
 *   <li>a non-clean result is one INFO line per module jar, naming the module and the count.</li>
 * </ul>
 * The summary is emitted once per module jar, by the entity scan that visits every class of the jar;
 * the main-class step does not audit, because the scan covers the main class too.
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
     * The least severe level {@link PluginLoggerBridge} forwards to the plugin logger. Named,
     * package-private and asserted on by {@code ClassloadFilterAuditTest} rather than left inline: the
     * live handler list on a JUL logger is global mutable state that other tests in the same JVM add to
     * and remove from, so a test that reads it back is order-dependent. This constant is the decision
     * itself.
     * <p>
     * It is INFO, not ALL, on purpose: {@link #record} logs FINE for every class {@link #classify}
     * returns a layer for, and that is every class of a third-party module (anything outside the seven
     * trusted prefixes), up to a thousand per module. Forwarding those would print up to a thousand
     * lines per module on top of the one INFO summary that is the operator-facing output. The logger
     * itself stays at ALL so a test- or debug-attached handler still receives FINE.
     */
    static final Level FORWARD_LEVEL = Level.INFO;

    static {
        // Load-bearing (see class javadoc): the only way this logger's records could reach
        // SystemLogHandler on their own is by propagating to the root logger, and this call removes
        // that path entirely.
        AUDIT_LOGGER.setUseParentHandlers(false);
        AUDIT_LOGGER.setLevel(Level.ALL);
        // With parent handlers off, the server's console sink is out of reach too. Forward INFO and above
        // to the plugin logger so an operator still sees a non-clean audit, at its real level.
        AUDIT_LOGGER.addHandler(new PluginLoggerBridge(FORWARD_LEVEL));
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
                + layer.describe() + " -- the class-name filters were removed in 6.3.0, so it was "
                + "allowed to load.");
    }

    /**
     * Emits exactly ONE summary for {@code moduleName}: one {@link Level#INFO} line when at least one
     * class would have been refused by a removed layer, or one {@link Level#FINE} line when none would
     * (a clean result is the expected case, not operator news). Resets the accumulator for
     * {@code moduleName} afterward, so a subsequent re-scan of the same module starts clean.
     * <p>
     * This differs deliberately from {@code ModuleScanDiagnostics.emitSummary}, which emits nothing
     * when its accumulator is empty: here a clean module is still a recorded measurement, just not one
     * that is shown by default.
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
        if (total == 0) {
            AUDIT_LOGGER.log(Level.FINE, "Module '" + moduleName + "' class-load audit: no class would have "
                    + "been refused by the class-name filters removed in 6.3.0.");
            return;
        }
        AUDIT_LOGGER.log(Level.INFO, "Module '" + moduleName + "' class-load audit: " + total
                + " class(es) would have been refused by the class-name filters removed in 6.3.0"
                + " (exact-name blacklist: " + counts[Layer.EXACT_BLACKLIST.ordinal()]
                + ", dangerous package prefix: " + counts[Layer.PACKAGE_PREFIX.ordinal()]
                + ", trusted-package whitelist: " + counts[Layer.WHITELIST.ordinal()]
                + ", suspicious keyword: " + counts[Layer.KEYWORD.ordinal()] + ")."
                + " Those filters no longer refuse anything, so the module loaded normally; this is for"
                + " information only.");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
