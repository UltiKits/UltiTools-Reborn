package com.ultikits.ultitools.manager;

import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jetbrains.annotations.ApiStatus;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.handler.ConsoleMirror;
import com.ultikits.ultitools.handler.SystemLogHandler;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequestHandler;
import com.ultikits.ultitools.websocket.PanelConnectionLog;
import com.ultikits.ultitools.websocket.SensitiveCommandRegistry;

/**
 * The framework's outbound producers on the v2 connection (plan 22-22, D-14, D-20, D-23, S-56); internal, not for module
 * use. Public only because the session wiring lives in {@code utils} while the encoders it feeds are package-private
 * here, like {@link RemoteRequestHandlers}.
 * <p>
 * <b>What it feeds.</b> Telemetry: {@link AgentTelemetry} sends {@code status} and the three snapshots after each
 * welcome, on its cadence, and answers {@code snapshot.request}; this class samples TPS and CPU once a second and the
 * server state every five seconds on the main thread through the package-private hooks of
 * {@link ServerMonitorManager}, without its v1 sender. Logs: a {@link SystemLogHandler} attached to the root logger
 * hands every record to a v2 {@link UltiPanelLogTransmitter}, whose single builder redacts it (22-04), and the redacted
 * line goes to {@link AgentLogBatcher}; a full batch is flushed at once on the agent thread, the rest every second.
 * Errors: the reports {@link ErrorReportCollector} grouped leave as {@code error.report} every second (at most
 * {@value #ERRORS_PER_SECOND}). Player events: the join, leave and command payloads come only from
 * {@link AgentEventEncoders} ({@link #playerRefPayload}, {@link #playerCommandPayload}).
 * <p>
 * <b>Only while connected.</b> A line logged while no connection is established is dropped, never buffered for the
 * next one (D-14), and a close clears what the batcher still held. The switches are read for every line and every
 * frame, so turning one off stops its frames without a new handshake.
 * <p>
 * <b>Threads.</b> {@link #established} and {@link #closed} run on the agent thread; {@link #mainThreadSecond()} on the
 * Bukkit main thread only; {@link #agentSecond()} on the agent thread; {@link #offerLine} on any logging thread.
 *
 * @since 6.3.1
 */
@ApiStatus.Internal
public final class AgentOutboundFeeds {

    /** The request type {@link #snapshotRequests()} answers. */
    public static final String SNAPSHOT_REQUEST = "snapshot.request";

    /** The server state is sampled on every fifth once-a-second run, as v1's 100-tick state task did. */
    static final int STATE_SAMPLE_PERIOD_SECONDS = 5;

    /** The most error reports sent in one second (the collector's own per-batch cap still applies). */
    static final int ERRORS_PER_SECOND = 10;

    private static final String ERROR_REPORT = "error.report";

    private final ServerMonitorManager monitor;
    private final AgentTelemetry telemetry;
    private final AgentLogBatcher logs;
    private final BooleanSupplier logsEnabled;
    private final BooleanSupplier errorsEnabled;
    private final Supplier<ErrorReportCollector> errors;
    private final UltiPanelLogTransmitter transmitter;
    private final AtomicBoolean flushQueued = new AtomicBoolean();

    /** The live channel between a welcome and its close, or {@code null}. */
    private volatile CloudChannel live;
    /** The agent thread of the live connection, for an immediate flush of a full batch. */
    private volatile Executor agentThread;
    /** The handler this object attached to the root logger, or {@code null}. Guarded by {@code this}. */
    private SystemLogHandler capture;
    /** Main thread only. */
    private int secondsSampled;

    /**
     * @param monitor          the server sampler, or {@code null} for no telemetry
     * @param telemetryEnabled the {@code telemetry} switch, read for every frame
     * @param logsEnabled      the {@code logs} switch, read for every line
     * @param errorsEnabled    the {@code errors} switch, read every second
     * @param errors           the error-report collector, or a supplier of {@code null} for no error reports
     * @param clock            epoch milliseconds for the cadence and the log rate bucket
     * @param mainThread       runs Bukkit work on the main thread, in order; {@code null} for no telemetry
     */
    public AgentOutboundFeeds(ServerMonitorManager monitor, BooleanSupplier telemetryEnabled,
                              BooleanSupplier logsEnabled, BooleanSupplier errorsEnabled,
                              Supplier<ErrorReportCollector> errors, LongSupplier clock, Executor mainThread) {
        this.monitor = monitor;
        this.telemetry = monitor == null || mainThread == null ? null
                : new AgentTelemetry(monitor, telemetryEnabled, clock, mainThread);
        this.logs = new AgentLogBatcher(clock);
        this.logsEnabled = logsEnabled;
        this.errorsEnabled = errorsEnabled;
        this.errors = errors;
        this.transmitter = new UltiPanelLogTransmitter(this::offerLine);
    }

    /** @return the {@code snapshot.request} handler, or {@code null} when there is no telemetry */
    public CloudRequestHandler snapshotRequests() {
        return telemetry;
    }

    /**
     * A connection was established: its channel becomes the live one, the telemetry sends its opening snapshots, and
     * the log capture is attached when the {@code logs} switch is on (again, if a logout detached it).
     *
     * @param channel     the connection's live channel
     * @param agentThread the connection's agent thread
     */
    public void established(CloudChannel channel, Executor agentThread) {
        logs.reset();
        this.agentThread = agentThread;
        this.live = channel;
        if (telemetry != null) {
            telemetry.onWelcome(channel);
        }
        if (logsEnabled.getAsBoolean()) {
            ensureLogCapture();
        }
    }

    /**
     * A connection closed: nothing is sent until the next welcome, and the lines not yet sent are dropped (D-14).
     *
     * @param channel the closed connection's channel; a close of an older connection does not clear a newer one
     */
    public void closed(CloudChannel channel) {
        if (live == channel) {
            live = null;
            agentThread = null;
        }
        logs.reset();
        if (telemetry != null) {
            telemetry.onClose();
        }
    }

    /**
     * The once-a-second main-thread run: one TPS and CPU sample, a state sample every
     * {@value #STATE_SAMPLE_PERIOD_SECONDS} seconds, then the telemetry cadence. Bukkit main thread only; scheduled every
     * 20 ticks so the TPS sample measures ticks, not wall-clock hops.
     */
    public void mainThreadSecond() {
        if (monitor == null) {
            return;
        }
        monitor.sampleTpsAndCpu();
        if (secondsSampled % STATE_SAMPLE_PERIOD_SECONDS == 0) {
            monitor.sampleAndPublishState();
        }
        secondsSampled++;
        CloudChannel channel = live;
        if (channel != null && telemetry != null) {
            telemetry.tick(channel);
        }
    }

    /** The once-a-second agent-thread run: sends the buffered log lines and the pending error reports. Never throws. */
    public void agentSecond() {
        CloudChannel channel = live;
        if (channel == null) {
            return;
        }
        try {
            logs.flush(channel);
            sendErrorReports(channel);
        } catch (RuntimeException failure) {
            PanelConnectionLog.log(Level.WARNING, "UltiCloud: the once-a-second send failed ("
                    + failure.getClass().getSimpleName() + ").");
        }
    }

    /**
     * Detaches the log capture and forgets the live connection; at plugin disable. Never waits for a thread.
     */
    public void shutdown() {
        live = null;
        agentThread = null;
        logs.reset();
        synchronized (this) {
            SystemLogHandler attached = capture;
            capture = null;
            if (attached != null) {
                Logger.getLogger("").removeHandler(attached);
            }
        }
    }

    /**
     * Sends what the collector grouped since the last second, while the {@code errors} switch is on. While it is off
     * or no connection is established the reports stay in the collector's bounded queue.
     */
    private void sendErrorReports(CloudChannel channel) {
        ErrorReportCollector collector = errors == null ? null : errors.get();
        if (collector == null || !errorsEnabled.getAsBoolean()) {
            return;
        }
        for (JsonObject payload : collector.drainErrorReportPayloads(ERRORS_PER_SECOND)) {
            channel.send(ERROR_REPORT, payload);
        }
    }

    /**
     * @param uuid the player's UUID string
     * @param name the player's name as the server reports it
     * @return the {@code player.join} / {@code player.leave} payload {@code { playerUuid, playerName }}, or
     *         {@code null} when the contract cannot carry this player
     */
    public static JsonObject playerRefPayload(String uuid, String name) {
        return AgentEventEncoders.playerRef(uuid, name);
    }

    /**
     * @param commandLine the command line as the player typed it
     * @return the {@code player.command} payload carrying only the normalised command name (D-20), or {@code null}
     *         when the name does not fit the contract
     */
    public static JsonObject playerCommandPayload(String commandLine) {
        return AgentEventEncoders.playerCommand(SensitiveCommandRegistry.commandName(commandLine));
    }

    /** One redacted line from the transmitter; dropped while offline or with the logs switch off. */
    void offerLine(long timestamp, Level level, String message) {
        if (live == null || !logsEnabled.getAsBoolean()) {
            return;
        }
        if (logs.offer(timestamp, level, message)) {
            requestFlush();
        }
    }

    /** A full batch leaves at once, on the agent thread, never inline on the logging thread (22-13). */
    private void requestFlush() {
        Executor executor = agentThread;
        if (executor == null || !flushQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                flushQueued.set(false);
                agentSecond();
            });
        } catch (RejectedExecutionException closing) {
            flushQueued.set(false);
        }
    }

    /**
     * Attaches the log handler to the root logger unless it is still attached (a logout's v1 log teardown detaches
     * every {@link SystemLogHandler}); the start-up records the early capture kept are replayed through it first.
     */
    private synchronized void ensureLogCapture() {
        final Logger root = Logger.getLogger("");
        SystemLogHandler current = capture;
        if (current != null && Arrays.asList(root.getHandlers()).contains(current)) {
            return;
        }
        final SystemLogHandler handler = new SystemLogHandler(transmitter);
        handler.loadConfiguration();
        capture = handler;
        ConsoleMirror.install();
        EarlyLogCapture.drainInto(handler.replayHandler(), () -> root.addHandler(handler));
    }

    /** @return the batcher, for tests */
    AgentLogBatcher logBatcher() {
        return logs;
    }
}
