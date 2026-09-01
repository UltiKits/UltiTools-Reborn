package com.ultikits.ultitools.utils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jetbrains.annotations.ApiStatus;

/**
 * Turns a silently-skipped class from a module's startup scan into a single, named, loud line an
 * operator actually reads (D-19).
 * <p>
 * {@code ComponentScanner} and {@code PluginManager} both catch {@code ClassNotFoundException |
 * LinkageError} per class during a module's scan and skip-and-continue — the module still loads,
 * every other command still registers, and the one stale command silently does not exist. This
 * class does not change that shape; it only makes it loud. Its API is deliberately small: {@link
 * #recordSkippedClass(String, String, Throwable)} accumulates one skipped class for the module
 * currently being scanned, and {@link #emitSummary(String)} — called once, after that module's
 * scan loop finishes — turns the accumulated list into exactly ONE {@link Level#SEVERE} record
 * naming the module, every skipped class, and pointing at {@code COMPATIBILITY.md}. A module that
 * skips nothing never calls the emitter at all, so a healthy startup produces no output from this
 * class whatsoever.
 * <p>
 * <b>Never routes through the root logger.</b> {@link #DIAGNOSTICS_LOGGER} disables parent
 * handlers, exactly as {@code RemoteActionLog} does and for the same measured reason:
 * {@code SystemLogHandler} attaches only to the root (empty-name) logger and
 * auto-reports any {@link Level#SEVERE} record carrying a {@link Throwable} into
 * {@code ErrorReportCollector} — which would turn this per-module summary into an error report
 * and risk the circular logging both classes' javadoc already warns about. The summary emitted by
 * {@link #emitSummary(String)} deliberately carries no {@link Throwable} for the same reason; the
 * individual causes live only on the per-class {@link Level#FINE} detail recorded by {@link
 * #recordSkippedClass(String, String, Throwable)}. Disabling parent handlers also disconnects this
 * logger from the console/{@code logs/latest.log} sink the server installs on the root logger, so
 * a dedicated {@link ConsoleHandler} is attached directly — see the static initializer below and
 * {@code 07-JAPICMP-BASELINE.md}'s "D-19 diagnostic observation" section for the real-server
 * confirmation that this line reaches {@code logs/latest.log}.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class ModuleScanDiagnostics {

    private static final Logger DIAGNOSTICS_LOGGER = Logger.getLogger(ModuleScanDiagnostics.class.getName());

    static {
        // Load-bearing (see class javadoc): the only way this logger's records could ever reach
        // SystemLogHandler is by propagating to the root logger, and this call removes that path
        // entirely, regardless of whether a Throwable happens to be attached.
        DIAGNOSTICS_LOGGER.setUseParentHandlers(false);
        DIAGNOSTICS_LOGGER.setLevel(Level.ALL);
        // setUseParentHandlers(false) above also disconnects this logger from the root logger's
        // own handlers -- the console/logs-latest.log sink the server installs there. A dedicated
        // handler restores that reach without restoring the SystemLogHandler path.
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(Level.ALL);
        DIAGNOSTICS_LOGGER.addHandler(consoleHandler);
    }

    /** One accumulator entry per module currently being scanned; cleared as each is emitted. */
    private static final Map<String, List<String>> SKIPPED_CLASSES_BY_MODULE = new ConcurrentHashMap<>();

    private ModuleScanDiagnostics() {
    }

    /**
     * Records one class that failed to load during {@code moduleName}'s scan, and immediately logs
     * the per-class detail at {@link Level#FINE} with {@code cause} attached — matching the shape
     * the GEN-07 audit evaluator (D-14) also uses, so the two diagnostics read as one pattern.
     * <p>
     * A {@code null} or blank {@code moduleName} or {@code className} is ignored entirely — neither
     * the accumulator nor the FINE detail record it — rather than producing a summary entry reading
     * the literal text {@code "null"}.
     *
     * @param moduleName the module (or, from {@code ComponentScanner}, the base package) whose scan
     *                    is in progress <br> 正在扫描的模块（在 {@code ComponentScanner} 中为基础包名）
     * @param className   the class that failed to load <br> 加载失败的类名
     * @param cause       the exception the scan caught; may be {@code null} <br> 扫描捕获的异常，可为 {@code null}
     */
    public static void recordSkippedClass(String moduleName, String className, Throwable cause) {
        if (isBlank(moduleName) || isBlank(className)) {
            return;
        }
        SKIPPED_CLASSES_BY_MODULE
                .computeIfAbsent(moduleName, key -> new CopyOnWriteArrayList<>())
                .add(className);
        DIAGNOSTICS_LOGGER.log(Level.FINE,
                "Module '" + moduleName + "' could not load scanned class '" + className + "'", cause);
    }

    /**
     * Emits ONE {@link Level#SEVERE} summary for {@code moduleName} if, and only if, at least one
     * class was recorded for it since the last call — then resets the accumulator for that module,
     * so a subsequent, cleanly-skip-free re-scan of the same module never repeats it. A module with
     * nothing recorded is a silent no-op: the emitter is never invoked at all.
     *
     * @param moduleName the module whose scan just finished <br> 刚完成扫描的模块
     */
    public static void emitSummary(String moduleName) {
        if (isBlank(moduleName)) {
            return;
        }
        List<String> skipped = SKIPPED_CLASSES_BY_MODULE.remove(moduleName);
        if (skipped == null || skipped.isEmpty()) {
            return;
        }
        String message = "Module '" + moduleName + "' skipped " + skipped.size()
                + " class(es) that failed to load during startup and continued loading without "
                + "them: " + String.join(", ", skipped) + ". This usually means the module was "
                + "built against an older UltiTools-API version -- see COMPATIBILITY.md for the "
                + "list of APIs removed or changed in this release.";
        DIAGNOSTICS_LOGGER.log(Level.SEVERE, message);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
