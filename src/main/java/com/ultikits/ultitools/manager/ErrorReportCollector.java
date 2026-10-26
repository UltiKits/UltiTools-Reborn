package com.ultikits.ultitools.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.exceptions.UltiToolsException;
import com.ultikits.ultitools.utils.ModuleIds;
import com.ultikits.ultitools.websocket.SensitiveCommandRegistry;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.ApiStatus;

/**
 * Collects, deduplicates, and rate-limits error reports before they reach the WebSocket.
 * <p>
 * Three-layer protection against DB bloat:
 * 1. Fingerprint dedup within 5-minute window
 * 2. Max 10 unique errors per batch cycle
 * 3. Sampling after 10 total occurrences of same error
 * <p>
 * This class NEVER uses java.util.logging.Logger to prevent circular logging.
 * All internal error output uses System.err.
 * <p>
 * On Paper, System.err does not bypass the logger: {@code SysoutCatcher} re-logs a plugin's
 * System.err line through that plugin's logger, so it can reach {@code SystemLogHandler} (#584).
 * That is safe here because of where the two lines run: {@link #reportError} is called from
 * {@code SystemLogHandler} (inside its same-thread re-entry guard, which drops the re-logged copy),
 * from command execution, the cooldown validator, the declarative GUI scheduler and the AOP exception
 * interceptor, and the configuration is loaded at start-up and reload. Only the first can run on a
 * thread holding a lock of the log stream, and there the re-entry guard drops the copy before the
 * transmitter is reached (and {@code UltiPanelLogTransmitter} never takes its batch lock on a thread
 * holding the drain lock); from every other caller the line at worst reaches the stream as an
 * ordinary line.
 *
 * @since 6.2.3
 */
@ApiStatus.Internal
public class ErrorReportCollector {

    private static final int MAX_QUEUE_SIZE = 200;
    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final int MAX_STACK_TRACE_LENGTH = 2048;
    private static final int MAX_STACK_FRAMES = 20;
    private static final int FINGERPRINT_STACK_FRAMES = 3;
    private static final long BYTES_PER_MB = 1024L * 1024L;

    // Config values (volatile for cross-thread visibility)
    private volatile boolean enabled = true;
    private volatile int dedupWindowSeconds = 300;
    private volatile int maxErrorsPerBatch = 10;
    private volatile int sampleAfterCount = 10;
    private volatile double sampleRate = 0.1;

    // Dedup map: fingerprint -> ErrorReport (reset every dedup window)
    private final ConcurrentHashMap<String, ErrorReport> dedupMap = new ConcurrentHashMap<>();

    // Queue of unique errors ready to be drained
    private final ConcurrentLinkedQueue<ErrorReport> errorQueue = new ConcurrentLinkedQueue<>();

    // Lifetime occurrence counter per fingerprint (never reset, used for sampling)
    private final ConcurrentHashMap<String, AtomicInteger> lifetimeCounters = new ConcurrentHashMap<>();

    // Scheduler for dedup window reset
    private ScheduledExecutorService scheduler;

    /**
     * Initialize the collector and start the dedup window reset scheduler.
     */
    public void init() {
        loadConfiguration();
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ErrorReportCollector-DedupReset");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::resetDedupWindow,
                dedupWindowSeconds, dedupWindowSeconds, TimeUnit.SECONDS);
    }

    /**
     * Shutdown the collector.
     */
    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }

    /**
     * Load configuration from UltiTools config.yml.
     */
    public void loadConfiguration() {
        try {
            UltiTools instance = UltiTools.getInstance();
            if (instance == null) return;

            enabled = instance.getConfig().getBoolean(
                    "ultipanel.logging.error-reporting.enabled", true);
            dedupWindowSeconds = instance.getConfig().getInt(
                    "ultipanel.logging.error-reporting.dedup-window-seconds", 300);
            maxErrorsPerBatch = instance.getConfig().getInt(
                    "ultipanel.logging.error-reporting.max-errors-per-batch", 10);
            sampleAfterCount = instance.getConfig().getInt(
                    "ultipanel.logging.error-reporting.sample-after-count", 10);
            sampleRate = instance.getConfig().getDouble(
                    "ultipanel.logging.error-reporting.sample-rate", 0.1);
        } catch (Exception e) {
            // NEVER use Logger here
            System.err.println("[UltiPanel] ErrorReportCollector: Failed to load config: " + e.getMessage());
        }
    }

    /**
     * Report an error. Thread-safe. Returns immediately if disabled.
     *
     * @param throwable  the exception
     * @param moduleName which UltiTools module caused it (e.g., "UltiChat")
     * @param ctx        trigger context (who/what caused this)
     */
    public void reportError(Throwable throwable, String moduleName, TriggerContext ctx) {
        if (!enabled) return;
        if (throwable == null) return;

        try {
            String fingerprint = computeFingerprint(throwable);

            // Layer 3: Sampling — after N total occurrences, only accept 1 in M
            AtomicInteger lifetime = lifetimeCounters.computeIfAbsent(
                    fingerprint, k -> new AtomicInteger(0));
            int totalCount = lifetime.incrementAndGet();
            if (totalCount > sampleAfterCount) {
                if (ThreadLocalRandom.current().nextDouble() >= sampleRate) {
                    return;
                }
            }

            // Layer 1: Fingerprint dedup within window
            ErrorReport existing = dedupMap.get(fingerprint);
            if (existing != null) {
                existing.incrementCount();
                return;
            }

            // Queue size check
            if (errorQueue.size() >= MAX_QUEUE_SIZE) {
                return;
            }

            // Create new report
            ErrorReport report = createReport(throwable, fingerprint, moduleName, ctx);
            ErrorReport prev = dedupMap.putIfAbsent(fingerprint, report);
            if (prev != null) {
                // Another thread beat us — just increment
                prev.incrementCount();
            } else {
                errorQueue.offer(report);
            }
        } catch (Exception e) {
            System.err.println("[UltiPanel] ErrorReportCollector: Failed to report error: " + e.getMessage());
        }
    }

    /**
     * Drain errors into a JsonArray for batch_update.
     * Layer 2: returns at most maxErrorsPerBatch, sorted by count descending.
     * Performs a final dedup pass to merge entries with the same fingerprint.
     *
     * @param max maximum number of errors to drain
     * @return JsonArray of error reports
     */
    public JsonArray drainErrors(int max) {
        JsonArray result = new JsonArray();
        for (ErrorReport report : drainReports(max)) {
            result.add(report.toJson());
        }
        return result;
    }

    /**
     * Drains the pending reports as v2 {@code error.report} payloads (plan 22-22, S-55), with the same limit, merge and
     * order as {@link #drainErrors(int)}. Each payload is built by {@link AgentEventEncoders#errorReport} from
     * {@link ErrorReport#view()}, so the message, stack trace and trigger detail are redacted exactly as the v1 report
     * is, and the fingerprint is this collector's group key padded to eight hex digits. A report the encoder cannot
     * make valid is dropped. A repeat of a report already drained inside the dedupe window is counted on it and not
     * sent again, as before.
     *
     * @param max the most payloads to return
     * @return the payloads, most frequent first; empty while error reporting is off
     */
    List<JsonObject> drainErrorReportPayloads(int max) {
        List<JsonObject> payloads = new ArrayList<>();
        for (ErrorReport report : drainReports(max)) {
            JsonObject payload = AgentEventEncoders.errorReport(report.view());
            if (payload != null) {
                payloads.add(payload);
            }
        }
        return payloads;
    }

    private List<ErrorReport> drainReports(int max) {
        if (!enabled) return Collections.emptyList();

        int limit = Math.min(max, maxErrorsPerBatch);
        List<ErrorReport> drained = new ArrayList<>();
        ErrorReport report;
        while ((report = errorQueue.poll()) != null && drained.size() < limit * 2) {
            drained.add(report);
        }

        if (drained.isEmpty()) return Collections.emptyList();

        // Final dedup pass — merge entries with same fingerprint
        Map<String, ErrorReport> merged = new HashMap<>();
        for (ErrorReport r : drained) {
            ErrorReport existing = merged.get(r.getFingerprint());
            if (existing != null) {
                existing.mergeFrom(r);
            } else {
                merged.put(r.getFingerprint(), r);
            }
        }

        // Sort by count descending (most frequent first)
        List<ErrorReport> sorted = new ArrayList<>(merged.values());
        Collections.sort(sorted, (a, b) -> Integer.compare(b.getCount(), a.getCount()));

        // Take at most limit
        List<ErrorReport> result = new ArrayList<>(sorted.subList(0, Math.min(limit, sorted.size())));

        // Put remaining back into queue
        for (int i = limit; i < sorted.size(); i++) {
            if (errorQueue.size() < MAX_QUEUE_SIZE) {
                errorQueue.offer(sorted.get(i));
            }
        }

        return result;
    }

    /**
     * Reset the dedup window — allows the same error to be reported again.
     */
    private void resetDedupWindow() {
        dedupMap.clear();
    }

    /**
     * @param throwable an exception
     * @return the group key this collector files it under; the panel's error group id is this key padded to eight hex
     *         digits (S-55)
     */
    static String fingerprintOf(Throwable throwable) {
        return computeFingerprint(throwable);
    }

    /**
     * Compute a fingerprint for dedup: hash of (exception class + top N stack frame method:line).
     */
    private static String computeFingerprint(Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append(throwable.getClass().getName());

        StackTraceElement[] frames = throwable.getStackTrace();
        int count = Math.min(FINGERPRINT_STACK_FRAMES, frames.length);
        for (int i = 0; i < count; i++) {
            sb.append('|');
            sb.append(frames[i].getMethodName());
            sb.append(':');
            sb.append(frames[i].getLineNumber());
        }

        return Integer.toHexString(sb.toString().hashCode());
    }

    /**
     * Create an ErrorReport from a throwable.
     */
    private ErrorReport createReport(Throwable throwable, String fingerprint,
                                     String moduleName, TriggerContext ctx) {
        ErrorReport report = new ErrorReport();
        report.fingerprint = fingerprint;
        report.timestamp = System.currentTimeMillis();
        report.firstSeenAt = report.timestamp;
        report.lastSeenAt = report.timestamp;
        report.count = new AtomicInteger(1);
        report.exceptionType = throwable.getClass().getName();

        // Error code from UltiToolsException
        if (throwable instanceof UltiToolsException) {
            report.errorCode = ((UltiToolsException) throwable).getErrorCode().getFormattedCode();
        } else {
            report.errorCode = "UNKNOWN";
        }

        // Message (truncated)
        String msg = throwable.getMessage();
        if (msg != null && msg.length() > MAX_MESSAGE_LENGTH) {
            msg = msg.substring(0, MAX_MESSAGE_LENGTH);
        }
        report.message = msg != null ? msg : throwable.getClass().getSimpleName();

        // Stack trace (truncated)
        report.stackTrace = formatStackTrace(throwable);

        // Module name, and the module's UltiCloud identifier for the v2 report
        report.moduleName = moduleName;
        report.moduleId = moduleIdentifierOf(moduleName);

        // Determine if this is a framework-internal error
        report.isFramework = computeIsFramework(throwable, moduleName);

        // Class and method from top stack frame
        StackTraceElement[] frames = throwable.getStackTrace();
        if (frames.length > 0) {
            String fullClassName = frames[0].getClassName();
            int lastDot = fullClassName.lastIndexOf('.');
            report.className = lastDot >= 0 ? fullClassName.substring(lastDot + 1) : fullClassName;
            report.methodName = frames[0].getMethodName();
        }

        // Trigger context
        if (ctx != null) {
            report.triggerType = ctx.getTriggerType().name();
            report.triggerDetail = ctx.getTriggerDetail();
            report.playerName = ctx.getPlayerName();
            report.playerUUID = ctx.getPlayerUUID();
        }

        // Server state
        captureServerState(report);

        return report;
    }

    /**
     * Capture current server state (TPS, memory, player count).
     */
    private void captureServerState(ErrorReport report) {
        try {
            Runtime runtime = Runtime.getRuntime();
            long maxMem = runtime.maxMemory();
            long usedMem = runtime.totalMemory() - runtime.freeMemory();
            report.serverMemoryPct = maxMem > 0
                    ? Math.round((double) usedMem / maxMem * 10000.0) / 100.0
                    : 0;
            report.memUsedMb = usedMem / BYTES_PER_MB;
            report.memMaxMb = maxMem > 0 && maxMem != Long.MAX_VALUE ? maxMem / BYTES_PER_MB : -1;

            UltiTools instance = UltiTools.getInstance();
            if (instance != null) {
                try {
                    report.playerCount = instance.getServer().getOnlinePlayers().size();
                } catch (Exception ignored) {
                    report.playerCount = -1;
                }

                // TPS from ServerMonitorManager if available
                ServerMonitorManager smm = instance.getServerMonitorManager();
                if (smm != null) {
                    try {
                        report.serverTPS = smm.getCurrentTPS();
                    } catch (Exception ignored) {
                        report.serverTPS = -1;
                    }
                }
            }
        } catch (Exception e) {
            // Never log from here
            report.serverTPS = -1;
            report.serverMemoryPct = -1;
            report.playerCount = -1;
            report.memUsedMb = -1;
            report.memMaxMb = -1;
        }
    }

    /**
     * Format stack trace to string, truncated to MAX_STACK_TRACE_LENGTH.
     */
    private String formatStackTrace(Throwable throwable) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);

            // Print just the top N frames
            pw.println(throwable.toString());
            StackTraceElement[] frames = throwable.getStackTrace();
            int count = Math.min(MAX_STACK_FRAMES, frames.length);
            for (int i = 0; i < count; i++) {
                pw.println("\tat " + frames[i]);
            }
            if (frames.length > count) {
                pw.println("\t... " + (frames.length - count) + " more");
            }

            // Include cause if present
            Throwable cause = throwable.getCause();
            if (cause != null) {
                pw.println("Caused by: " + cause.toString());
                StackTraceElement[] causeFrames = cause.getStackTrace();
                int causeCount = Math.min(5, causeFrames.length);
                for (int i = 0; i < causeCount; i++) {
                    pw.println("\tat " + causeFrames[i]);
                }
                if (causeFrames.length > causeCount) {
                    pw.println("\t... " + (causeFrames.length - causeCount) + " more");
                }
            }

            pw.flush();
            String result = sw.toString();
            if (result.length() > MAX_STACK_TRACE_LENGTH) {
                result = result.substring(0, MAX_STACK_TRACE_LENGTH);
            }
            return result;
        } catch (Exception e) {
            return throwable.getClass().getName() + ": " + throwable.getMessage();
        }
    }

    /**
     * Determine whether an error originates from the UltiTools framework itself
     * (as opposed to a plugin module loaded by the framework).
     * <p>
     * A framework error satisfies BOTH:
     * 1. The top stack frame's class is in the com.ultikits.ultitools package.
     * 2. The module name indicates the framework (null, empty, or "UltiTools").
     */
    private boolean computeIsFramework(Throwable throwable, String moduleName) {
        boolean moduleIsFramework = (moduleName == null || moduleName.isEmpty()
                || "UltiTools".equals(moduleName));

        if (!moduleIsFramework) {
            return false;
        }

        StackTraceElement[] frames = throwable.getStackTrace();
        if (frames.length == 0) {
            return true;
        }
        String topClass = frames[0].getClassName();
        return topClass.startsWith("com.ultikits.ultitools.");
    }

    /**
     * The UltiCloud identifier of the loaded module a report names (22-26 fix round, backend contract
     * answer of 2026-10-27): the server groups an {@code error.report} to a module by matching its
     * {@code moduleName} against the catalogue identifier, so the v2 report carries the module's
     * identify-string, lower-case ({@link ModuleIds}), never free text. A name is attributed to a
     * loaded module when it equals that module's name or identify-string, ignoring case; anything
     * else -- the framework itself, a Bukkit plugin, the server, a command description, a bean name --
     * gives {@code null}, which the contract allows. Never throws.
     *
     * @param moduleName the name a producer passed, possibly {@code null}
     * @return the identifier, or {@code null} when the report is not attributable to a loaded module
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // a report must never fail over attribution
    static String moduleIdentifierOf(String moduleName) {
        if (moduleName == null || moduleName.trim().isEmpty()) {
            return null;
        }
        try {
            UltiTools instance = UltiTools.getInstance();
            PluginManager modules = instance == null ? null : instance.getPluginManager();
            List<UltiToolsPlugin> loaded = modules == null ? null : modules.getPluginList();
            if (loaded == null) {
                return null;
            }
            String name = moduleName.trim();
            for (UltiToolsPlugin module : loaded) {
                if (module != null && (name.equalsIgnoreCase(module.getPluginName())
                        || name.equalsIgnoreCase(module.getIdentifyString()))) {
                    return ModuleIds.of(module);
                }
            }
        } catch (RuntimeException unavailable) {
            return null;
        }
        return null;
    }

    /**
     * Redacts registered commands' arguments from a field bound for UltiCloud. Without a framework
     * instance nothing can have been registered, so the text is returned unchanged.
     */
    private static String redact(String text) {
        UltiTools instance = UltiTools.getInstance();
        SensitiveCommandRegistry registry = instance == null ? null : instance.getSensitiveCommandRegistry();
        return registry == null ? text : registry.redactText(text);
    }

    /**
     * Check if error reporting is enabled.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    // ========== Inner class ==========

    /**
     * Error report data structure.
     */
    static class ErrorReport {
        String fingerprint;
        String errorCode;
        String message;
        String stackTrace;
        String moduleName;
        /** The module's UltiCloud identifier for the v2 report, or {@code null}; see {@link #moduleIdentifierOf}. */
        String moduleId;
        String className;
        String methodName;
        String triggerType;
        String triggerDetail;
        String playerName;
        String playerUUID;
        double serverTPS;
        double serverMemoryPct;
        int playerCount;
        long timestamp;
        AtomicInteger count;
        boolean isFramework;
        /** The exception's binary class name, for the v2 report. */
        String exceptionType;
        /** Used heap in megabytes when the report was created, or -1. */
        long memUsedMb = -1;
        /** Maximum heap in megabytes, or -1 when unknown. */
        long memMaxMb = -1;
        /** When the group was first seen, in milliseconds. */
        volatile long firstSeenAt;
        /** When the group was last counted, in milliseconds. */
        volatile long lastSeenAt;

        String getFingerprint() {
            return fingerprint;
        }

        int getCount() {
            return count.get();
        }

        void incrementCount() {
            count.incrementAndGet();
            lastSeenAt = System.currentTimeMillis();
        }

        /**
         * Merge another report into this one (sum counts, keep latest context).
         */
        void mergeFrom(ErrorReport other) {
            count.addAndGet(other.getCount());
            firstSeenAt = Math.min(firstSeenAt, other.firstSeenAt);
            lastSeenAt = Math.max(lastSeenAt, other.lastSeenAt);
            if (other.timestamp > this.timestamp) {
                this.timestamp = other.timestamp;
                this.triggerDetail = other.triggerDetail;
                this.playerName = other.playerName;
                this.playerUUID = other.playerUUID;
                this.serverTPS = other.serverTPS;
                this.serverMemoryPct = other.serverMemoryPct;
                this.playerCount = other.playerCount;
            }
        }

        /**
         * The report's values for the v2 {@code error.report} encoder, field by field (plan 22-22): the raw group key
         * as fingerprint (the encoder pads it), the exception class, the redacted message, stack trace and trigger
         * detail, the module's UltiCloud identifier ({@code null} when the report names no loaded module), the
         * trigger type name, the player name, {@code className.methodName} of the top frame as location, and the server snapshot with every unknown value ({@code -1}) as {@code null}.
         *
         * @return a view over this report
         */
        AgentEventEncoders.ErrorReportView view() {
            final String redactedMessage = redact(message);
            final String redactedStackTrace = redact(stackTrace);
            final String redactedDetail = redact(triggerDetail);
            return new AgentEventEncoders.ErrorReportView() {
                @Override public String fingerprint() { return fingerprint; }
                @Override public String exceptionType() { return exceptionType; }
                @Override public String message() { return redactedMessage; }
                @Override public String stackTrace() { return redactedStackTrace; }
                @Override public String moduleName() { return moduleId; }
                @Override public String triggerKind() { return triggerType; }
                @Override public String triggerDetail() { return redactedDetail; }
                @Override public String playerName() { return playerName; }
                @Override public String location() {
                    return className == null ? null : className + "." + methodName;
                }
                @Override public Double tps() { return serverTPS < 0 ? null : serverTPS; }
                @Override public Long memUsedMb() { return memUsedMb < 0 ? null : memUsedMb; }
                @Override public Long memMaxMb() { return memMaxMb < 0 ? null : memMaxMb; }
                @Override public Long onlinePlayers() { return playerCount < 0 ? null : (long) playerCount; }
                @Override public int occurrences() { return count.get(); }
                @Override public long firstSeenAt() { return firstSeenAt; }
                @Override public long lastSeenAt() { return lastSeenAt; }
            };
        }

        /**
         * Serialises the report for UltiCloud. {@code message}, {@code stackTrace} and
         * {@code triggerDetail} pass through {@link SensitiveCommandRegistry#redactText} here, at
         * serialisation time only: this report and its fingerprint keep the original text, so
         * de-duplication groups exactly as before and nothing local changes. A trigger detail may
         * carry a whole command line ({@link TriggerContext#command}), and an exception message or
         * stack trace may quote one.
         */
        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("fingerprint", fingerprint);
            json.addProperty("errorCode", errorCode);
            json.addProperty("message", redact(message));
            json.addProperty("stackTrace", redact(stackTrace));
            json.addProperty("moduleName", moduleName);
            json.addProperty("className", className);
            json.addProperty("methodName", methodName);
            json.addProperty("triggerType", triggerType);
            json.addProperty("triggerDetail", redact(triggerDetail));
            json.addProperty("playerName", playerName);
            json.addProperty("playerUUID", playerUUID);
            json.addProperty("serverTPS", serverTPS);
            json.addProperty("serverMemoryPct", serverMemoryPct);
            json.addProperty("playerCount", playerCount);
            json.addProperty("occurrenceCount", count.get());
            json.addProperty("timestamp", timestamp);
            json.addProperty("isFramework", isFramework);
            return json;
        }
    }
}
