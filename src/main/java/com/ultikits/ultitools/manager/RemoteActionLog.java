package com.ultikits.ultitools.manager;

import java.io.File;
import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.jetbrains.annotations.ApiStatus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Capability;

/**
 * The framework's own durable record of what the panel actually did — every capability-gate
 * decision, allowed or denied, as one JSON line in the active file under
 * {@code plugins/UltiTools/security/action.log.<generation>}. Generation {@code 0} is always the
 * currently-active file — see {@link #init(File)} for why the literal, suffix-free
 * {@code action.log} never exists on disk.
 * <p>
 * Replaces the audit/enforce mode ROADMAP originally asked for (D-22): there is only one policy
 * tier, so there is nothing to simulate, only something to record. This class is constructed
 * unconditionally in {@code UltiTools.initWebSocketManagers()} and is not gated by any
 * {@link Capability} — D-32.
 * <p>
 * <b>Disabling this logger's parent handlers (see the static initializer below) is load-bearing.</b>
 * {@code SystemLogHandler} attaches only to the root logger ({@code Logger.getLogger("")}) — the
 * sole attachment site is {@code LogStreamManager.java} line 95, {@code rootLogger.addHandler(systemLogHandler)}.
 * A non-root logger with parent handlers disabled therefore cannot reach {@code SystemLogHandler}
 * and cannot create the feedback loop {@link ErrorReportCollector}'s class javadoc warns about
 * ("This class NEVER uses java.util.logging.Logger to prevent circular logging"). This class may
 * therefore safely use {@link Logger}/{@link FileHandler} where {@code ErrorReportCollector} may
 * not — that is a deliberate, narrower reading of the same hazard, not a violation of it.
 * Internal failures of the log itself (handler creation, directory creation, config load) print to
 * {@link System#err}, never through any {@link Logger} — the same discipline
 * {@code ErrorReportCollector} uses for its own internal failures, for the same reason: a failure
 * in the thing that logs must not try to log itself.
 * <p>
 * 本框架自身对面板实际行为的持久化记录——每一次能力网关的裁决，无论放行还是拒绝，都作为一行
 * JSON 写入 {@code plugins/UltiTools/security/action.log.<代数>} 中当前活跃的那个文件——代数 0
 * 始终是当前活跃文件，磁盘上不存在不带后缀的 {@code action.log}，见 {@link #init(File)}。它在
 * {@code UltiTools.initWebSocketManagers()} 中被无条件构造，不受任何 {@link Capability} 拦截（D-32）。
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public class RemoteActionLog {

    private static final String LOGGER_NAME = "UltiPanel.ActionLog";
    private static final int DEFAULT_MAX_SIZE_BYTES = 1_048_576;
    private static final int DEFAULT_MAX_FILES = 5;

    private static final Logger ACTION_LOG = Logger.getLogger(LOGGER_NAME);

    static {
        // Load-bearing — see class javadoc: this is the only call in this class that disables
        // parent handlers, and it is what keeps this logger from ever reaching SystemLogHandler.
        ACTION_LOG.setUseParentHandlers(false);
        ACTION_LOG.setLevel(Level.ALL);
    }

    // serializeNulls() is required for a stable field set: without it, Gson's JsonWriter drops any
    // member whose value is JsonNull — including one added explicitly via
    // JsonObject.addProperty(name, null) — so an ALLOWED entry's "reason" key would still vanish
    // from the serialized line even after JsonLineFormatter stops special-casing null. This Gson
    // instance is private to RemoteActionLog and used for nothing but this one JSON line per
    // record() call (see JsonLineFormatter below) — no other class or output shares it, so
    // serializeNulls()'s effect is confined to this log's own lines and does not reach the
    // WebSocket managers' separately-constructed Gson instances that build the same way.
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private volatile int maxSizeBytes = DEFAULT_MAX_SIZE_BYTES;
    private volatile int maxFiles = DEFAULT_MAX_FILES;

    /**
     * The {@link FileHandler} this specific instance attached to the shared static
     * {@link #ACTION_LOG} — {@code null} until a successful {@link #init(File)}. Tracked per
     * instance, not read back off {@link #ACTION_LOG} itself, so {@link #shutdown()} removes only
     * the handler this instance is responsible for (WR-01) — never a sibling instance's handler,
     * which matters if two instances are ever briefly alive at once.
     */
    private volatile FileHandler attachedHandler;

    /**
     * Attaches the rotating {@link FileHandler} at
     * {@code <dataFolder>/security/action.log.%g}, creating the {@code security} directory if
     * absent. Loads the rotation knobs from config.yml first — see {@link #loadConfiguration()}.
     * <p>
     * <b>The file on disk is never literally {@code action.log}.</b> {@link FileHandler} always
     * substitutes {@code %g} with the current generation number when the pattern contains it, and
     * — confirmed empirically against this JDK's {@link FileHandler}, not merely inferred from its
     * javadoc — it auto-appends the same suffix even if {@code %g} were removed from the pattern,
     * because {@code max-files} defaults to {@value #DEFAULT_MAX_FILES} ({@code count > 1}): a
     * generation suffix is unavoidable at any rotation-enabled setting, and only an operator
     * setting {@code max-files} to {@code 1} (disabling rotation entirely) would remove it.
     * Generation {@code 0} is always the currently-active file that new records append to;
     * {@code action.log.1}, {@code action.log.2}, … are older rotated generations, oldest evicted
     * first once {@code max-files} is reached. Every operator-facing reference to this file's path
     * must say {@code action.log.0} (the active file), not the bare, never-existing
     * {@code action.log} — see UAT finding 5b.
     *
     * @param dataFolder the plugin's data folder ({@code getDataFolder()})
     */
    public void init(File dataFolder) {
        loadConfiguration();
        try {
            File securityDir = new File(dataFolder, "security");
            if (!securityDir.exists() && !securityDir.mkdirs()) {
                System.err.println(
                        "[UltiPanel] RemoteActionLog: failed to create security directory: " + securityDir);
                return;
            }
            File logPattern = new File(securityDir, "action.log.%g");
            FileHandler handler = new FileHandler(logPattern.getPath(), maxSizeBytes, maxFiles, true);
            handler.setFormatter(new JsonLineFormatter(gson));
            handler.setLevel(Level.ALL);
            ACTION_LOG.addHandler(handler);
            attachedHandler = handler;
        } catch (IOException | SecurityException e) {
            // NEVER use Logger here — this failure is about the log itself.
            System.err.println("[UltiPanel] RemoteActionLog: failed to attach file handler: " + e.getMessage());
        }
    }

    /**
     * Removes and closes the {@link FileHandler} this instance attached in {@link #init(File)}
     * (WR-01, 06-REVIEW.md).
     * <p>
     * A no-op if {@link #init(File)} was never called or failed to attach a handler
     * ({@link #attachedHandler} stays {@code null}). Flushes before closing so no buffered line is
     * lost. Load-bearing for a Bukkit {@code /reload}: {@link #ACTION_LOG} is a {@code static}
     * {@link Logger} shared for the life of the JVM, so without this call a second
     * {@code onEnable}'s {@link #init(File)} would attach a <em>second</em> {@link FileHandler} on
     * top of this one, and every subsequent {@link #record(Entry)} would be delivered to both —
     * duplicating every action-log line from that point on, exactly the defect this method exists
     * to prevent.
     */
    public void shutdown() {
        FileHandler handler = attachedHandler;
        if (handler == null) {
            return;
        }
        try {
            handler.flush();
            ACTION_LOG.removeHandler(handler);
            handler.close();
        } catch (SecurityException e) {
            // NEVER use Logger here — this failure is about the log itself.
            System.err.println("[UltiPanel] RemoteActionLog: failed to detach file handler: " + e.getMessage());
        } finally {
            attachedHandler = null;
        }
    }

    /**
     * Loads {@code ultipanel.logging.action-log.max-size-bytes} and {@code .max-files}, copying
     * {@code ErrorReportCollector.loadConfiguration()}'s exact shape: {@link UltiTools#getInstance()}
     * null-guard, {@code instance.getConfig().getInt(path, default)} per key, one try/catch whose
     * failure branch prints to {@link System#err} and never to a {@link Logger} — this failure is
     * about loading config for this very log, so it cannot log through itself. There is
     * deliberately no {@code enabled} key here and no {@link Capability} consulted — D-32 makes
     * this log's existence non-negotiable for the operator, a narrow exception to the D-04
     * no-floor rule (which governs which commands may run, not whether the record of what ran
     * survives).
     */
    private void loadConfiguration() {
        try {
            UltiTools instance = UltiTools.getInstance();
            if (instance == null) {
                return;
            }
            maxSizeBytes = instance.getConfig().getInt(
                    "ultipanel.logging.action-log.max-size-bytes", DEFAULT_MAX_SIZE_BYTES);
            maxFiles = instance.getConfig().getInt(
                    "ultipanel.logging.action-log.max-files", DEFAULT_MAX_FILES);
        } catch (Exception e) {
            // NEVER use Logger here — this failure is about the log itself.
            System.err.println("[UltiPanel] RemoteActionLog: failed to load config: " + e.getMessage());
        }
    }

    /**
     * Records one entry. A no-op if {@code entry} is {@code null}.
     *
     * @param entry the entry to record
     */
    public void record(Entry entry) {
        if (entry == null) {
            return;
        }
        LogRecord record = new LogRecord(Level.INFO, "");
        record.setParameters(new Object[] {entry});
        ACTION_LOG.log(record);
    }

    /**
     * Allowed or denied — the two verdicts a capability gate can reach.
     */
    public enum Verdict {
        ALLOWED,
        DENIED
    }

    /**
     * One immutable action-log entry. Built exactly like {@link TriggerContext}: private final
     * fields, a private constructor, and named static factories. {@code actor} is captured as a
     * plain {@link String} rather than a live identity — the framework cannot attribute a remote
     * command to an individual panel operator today because {@code executeCommandInternal}
     * dispatches every remote command as the console sender regardless of the {@code executor}
     * field's value; this records the received value verbatim, or the literal {@code "panel"} when
     * absent, rather than inventing a per-operator identity the framework cannot back up.
     */
    public static final class Entry {
        private final long timestamp;
        private final String capability;
        private final String action;
        private final String target;
        private final String actor;
        private final Verdict verdict;
        private final String reason;

        private Entry(long timestamp, String capability, String action, String target, String actor,
                       Verdict verdict, String reason) {
            this.timestamp = timestamp;
            this.capability = capability;
            this.action = action;
            this.target = target;
            this.actor = actor;
            this.verdict = verdict;
            this.reason = reason;
        }

        /**
         * Builds an {@link Verdict#ALLOWED} entry.
         *
         * @param capability the capability that gated this action
         * @param action     the message type, extended with the resolved sub-operation where one exists
         * @param target     the command text, file path, or message type
         * @param actor      the inbound {@code executor} field verbatim, or {@code "panel"} if absent
         * @return the entry
         */
        public static Entry allowed(Capability capability, String action, String target, String actor) {
            return new Entry(System.currentTimeMillis(), capability.name(), action, target, actor,
                    Verdict.ALLOWED, null);
        }

        /**
         * Builds a {@link Verdict#DENIED} entry.
         *
         * @param capability the capability that gated this action
         * @param action     the message type, extended with the resolved sub-operation where one exists
         * @param target     the command text, file path, or message type
         * @param actor      the inbound {@code executor} field verbatim, or {@code "panel"} if absent
         * @param reason     the refusal text — matches what was sent to the panel (D-05/D-17)
         * @return the entry
         */
        public static Entry denied(Capability capability, String action, String target, String actor,
                                    String reason) {
            return new Entry(System.currentTimeMillis(), capability.name(), action, target, actor,
                    Verdict.DENIED, reason);
        }

        public long getTimestamp() {
            return timestamp;
        }

        public String getCapability() {
            return capability;
        }

        public String getAction() {
            return action;
        }

        public String getTarget() {
            return target;
        }

        public String getActor() {
            return actor;
        }

        public Verdict getVerdict() {
            return verdict;
        }

        public String getReason() {
            return reason;
        }
    }

    /**
     * Serializes one {@link Entry} per {@link LogRecord} into a single compact JSON line — no
     * pretty-printing — using the same {@code GsonBuilder().disableHtmlEscaping()} construction the
     * WebSocket managers already use.
     */
    static final class JsonLineFormatter extends Formatter {
        private final Gson formatterGson;

        JsonLineFormatter(Gson formatterGson) {
            this.formatterGson = formatterGson;
        }

        @Override
        public String format(LogRecord record) {
            Object[] params = record.getParameters();
            if (params == null || params.length == 0 || !(params[0] instanceof Entry)) {
                return "";
            }
            Entry entry = (Entry) params[0];
            JsonObject json = new JsonObject();
            json.addProperty("timestamp", entry.timestamp);
            json.addProperty("capability", entry.capability);
            json.addProperty("action", entry.action);
            json.addProperty("target", entry.target);
            json.addProperty("actor", entry.actor);
            json.addProperty("verdict", entry.verdict.name());
            // Always emit "reason" — even when null — so ALLOWED and DENIED rows carry the same
            // key set. JsonObject.addProperty(String, String) stores JsonNull.INSTANCE for a null
            // value rather than omitting the member, which is exactly the stable-shape property a
            // consumer of this log (the compensating control for the framework's no-floor command
            // policy) needs: it must never have to branch on verdict to know which fields exist.
            json.addProperty("reason", entry.reason);
            return formatterGson.toJson(json) + System.lineSeparator();
        }
    }
}
