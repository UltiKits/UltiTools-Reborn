package com.ultikits.ultitools.manager;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
 * <p>
 * <b>Resolved at first use (22-22 fix).</b> The plugin creates this object before {@code initWebSocketManagers()}
 * creates the {@link ServerMonitorManager}, so the monitor is never read at construction: {@link #telemetryAvailable()}
 * and every telemetry path ask the supplier until it yields a monitor, and keep the first one it yields. The error
 * collector is read every second for the same reason. Nothing else this object holds depends on an object the plugin
 * creates later.
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

    private final Supplier<ServerMonitorManager> monitorSource;
    private final BooleanSupplier telemetryEnabled;
    private final LongSupplier clock;
    private final Executor mainThread;
    private final Consumer<List<String>> snapshotRead;
    private final Object telemetryLock = new Object();
    /** The first monitor the supplier yielded, or {@code null} before. Written under {@code telemetryLock}. */
    private volatile ServerMonitorManager monitor;
    /** The telemetry over {@link #monitor}, or {@code null} before a monitor exists. Same guard. */
    private volatile AgentTelemetry telemetry;
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
    /** Whether the gateway accepted {@code logs} on the live connection; {@code false} while none is live. */
    private volatile boolean logsAccepted;
    /** The handler this object attached to the root logger, or {@code null}. Guarded by {@code this}. */
    private SystemLogHandler capture;
    /** Main thread only. */
    private int secondsSampled;
    /** Whether the monitor's TPS clock was started by the first run. Main thread only. */
    private boolean tpsClockPrimed;

    /**
     * Builds the feeds with auditing for served snapshot requests.
     *
     * @param monitor supplies the server sampler, resolved at first use
     * @param telemetryEnabled the telemetry switch
     * @param logsEnabled the logs switch
     * @param errorsEnabled the errors switch
     * @param errors supplies the error-report collector
     * @param clock epoch milliseconds for the cadence and rate bucket
     * @param mainThread runs Bukkit work on the main thread
     * @param snapshotRead records the targets of each served snapshot request
     */
    public AgentOutboundFeeds(Supplier<ServerMonitorManager> monitor, BooleanSupplier telemetryEnabled,
                              BooleanSupplier logsEnabled, BooleanSupplier errorsEnabled,
                              Supplier<ErrorReportCollector> errors, LongSupplier clock, Executor mainThread,
                              Consumer<List<String>> snapshotRead) {
        this.snapshotRead = Objects.requireNonNull(snapshotRead, "snapshotRead");
        this.monitorSource = monitor;
        this.telemetryEnabled = telemetryEnabled;
        this.clock = clock;
        this.mainThread = mainThread;
        this.logs = new AgentLogBatcher(clock);
        this.logsEnabled = logsEnabled;
        this.errorsEnabled = errorsEnabled;
        this.errors = errors;
        this.transmitter = new UltiPanelLogTransmitter(new UltiPanelLogTransmitter.AgentLineSink() {
            @Override
            public void offer(long timestamp, Level level, String message) {
                offerLine(timestamp, level, message);
            }

            @Override
            public boolean accepting() {
                return linesWanted();
            }
        });
    }

    /** @return the {@code snapshot.request} handler, or {@code null} while there is no telemetry */
    public CloudRequestHandler snapshotRequests() {
        return telemetry();
    }

    /**
     * Whether the telemetry can run now: a monitor exists and the main thread is bound. The hello declares
     * {@code telemetry} only when this is true, so the hello and the router agree.
     *
     * @return {@code true} once a monitor was obtained
     */
    public boolean telemetryAvailable() {
        return telemetry() != null;
    }

    /** The telemetry, built over the first monitor the supplier yields; {@code null} while it yields none. */
    private AgentTelemetry telemetry() {
        AgentTelemetry current = telemetry;
        if (current != null || monitorSource == null || mainThread == null) {
            return current;
        }
        synchronized (telemetryLock) {
            if (telemetry == null) {
                ServerMonitorManager found;
                try {
                    found = monitorSource.get();
                } catch (RuntimeException failure) {
                    found = null;
                }
                if (found != null) {
                    monitor = found;
                    telemetry = new AgentTelemetry(found, telemetryEnabled, clock, mainThread, snapshotRead);
                }
            }
            return telemetry;
        }
    }

    /**
     * A connection was established: its channel becomes the live one, the telemetry sends its opening snapshots, and
     * the log capture is attached when the {@code logs} switch is on (again, if a logout detached it).
     * <p>
     * When the gateway did not accept {@code logs}, the line stream is inert for this connection (22-22 fix): no line
     * is built, batched or flushed, so a refused {@code log.lines} never turns every following line into a flush
     * attempt. The handler itself may stay attached, because it also feeds the error collector.
     *
     * @param channel      the connection's live channel
     * @param agentThread  the connection's agent thread
     * @param logsAccepted whether the welcome accepted {@code logs}
     */
    public void established(CloudChannel channel, Executor agentThread, boolean logsAccepted) {
        logs.reset();
        this.agentThread = agentThread;
        this.logsAccepted = logsAccepted;
        this.live = channel;
        AgentTelemetry current = telemetry();
        if (current != null) {
            current.onWelcome(channel);
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
            logsAccepted = false;
        }
        logs.reset();
        AgentTelemetry current = telemetry;
        if (current != null) {
            current.onClose();
        }
    }

    /**
     * The once-a-second main-thread run: one TPS and CPU sample (the first run only starts the TPS clock), a state
     * sample every {@value #STATE_SAMPLE_PERIOD_SECONDS} seconds, then the telemetry cadence. Bukkit main thread only;
     * scheduled every 20 ticks so the TPS sample measures ticks, not wall-clock hops.
     */
    public void mainThreadSecond() {
        AgentTelemetry current = telemetry();
        ServerMonitorManager sampler = monitor;
        if (current == null || sampler == null) {
            return;
        }
        if (tpsClockPrimed) {
            sampler.sampleTpsAndCpu();
        } else {
            // The first run starts the clock: the time since the monitor was created is not a tick measurement.
            tpsClockPrimed = sampler.primeTpsClock();
        }
        if (secondsSampled % STATE_SAMPLE_PERIOD_SECONDS == 0) {
            sampler.sampleAndPublishState();
        }
        secondsSampled++;
        CloudChannel channel = live;
        if (channel != null) {
            current.tick(channel);
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
        logsAccepted = false;
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

    /**
     * One redacted line from the transmitter; dropped while offline, with the logs switch off, or when the gateway did
     * not accept {@code logs} on this connection.
     */
    void offerLine(long timestamp, Level level, String message) {
        if (!linesWanted()) {
            return;
        }
        if (logs.offer(timestamp, level, message)) {
            requestFlush();
        }
    }

    /** @return whether a line would be sent: a connection is live, accepted {@code logs}, and the switch is on */
    private boolean linesWanted() {
        return live != null && logsAccepted && logsEnabled.getAsBoolean();
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
