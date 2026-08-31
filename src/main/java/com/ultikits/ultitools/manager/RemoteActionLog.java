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
import com.ultikits.ultitools.entities.Capability;

/**
 * The framework's own durable record of what the panel actually did — every capability-gate
 * decision, allowed or denied, as one JSON line in {@code plugins/UltiTools/security/action.log}.
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
 * JSON 写入 {@code plugins/UltiTools/security/action.log}。它在 {@code UltiTools.initWebSocketManagers()}
 * 中被无条件构造，不受任何 {@link Capability} 拦截（D-32）。
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

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * Attaches the rotating {@link FileHandler} at
     * {@code <dataFolder>/security/action.log.%g}, creating the {@code security} directory if
     * absent. Rotation limit and count are fixed at {@value #DEFAULT_MAX_SIZE_BYTES} bytes and
     * {@value #DEFAULT_MAX_FILES} files in this task; a later task makes them operator-configurable
     * under {@code ultipanel.logging.action-log.*}.
     *
     * @param dataFolder the plugin's data folder ({@code getDataFolder()})
     */
    public void init(File dataFolder) {
        try {
            File securityDir = new File(dataFolder, "security");
            if (!securityDir.exists() && !securityDir.mkdirs()) {
                System.err.println(
                        "[UltiPanel] RemoteActionLog: failed to create security directory: " + securityDir);
                return;
            }
            File logPattern = new File(securityDir, "action.log.%g");
            FileHandler handler = new FileHandler(logPattern.getPath(),
                    DEFAULT_MAX_SIZE_BYTES, DEFAULT_MAX_FILES, true);
            handler.setFormatter(new JsonLineFormatter(gson));
            handler.setLevel(Level.ALL);
            ACTION_LOG.addHandler(handler);
        } catch (IOException | SecurityException e) {
            // NEVER use Logger here — this failure is about the log itself.
            System.err.println("[UltiPanel] RemoteActionLog: failed to attach file handler: " + e.getMessage());
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
            if (entry.reason != null) {
                json.addProperty("reason", entry.reason);
            }
            return formatterGson.toJson(json) + System.lineSeparator();
        }
    }
}
