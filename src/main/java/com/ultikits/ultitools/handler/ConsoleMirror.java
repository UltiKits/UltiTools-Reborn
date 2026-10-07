package com.ultikits.ultitools.handler;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.EarlyLogCapture;

/**
 * Makes the panel's log stream a mirror of the server console (as of 6.3.0).
 * <p>
 * <b>Why it exists.</b> The stream's handler, {@link SystemLogHandler}, sits on the
 * {@code java.util.logging} root logger, which receives every plugin's lines. Paper prints its own
 * output through Log4j instead: command feedback ({@code MinecraftServer#sendSystemMessage}), a
 * module's reply to the console sender ({@code TerminalConsoleCommandSender}, a logger named after
 * Log4j's root logger), joins, chat, vanilla warnings and player command lines. Log4j never hands
 * those to {@code java.util.logging}, so before this class none of them reached the panel -- in
 * particular not the output of a command the panel itself sent.
 * <p>
 * <b>How.</b> An appender on Log4j's root logger converts each event into a {@link LogRecord} and
 * hands it to the stream handler currently attached to the {@code java.util.logging} root logger:
 * {@link EarlyLogCapture} before the panel connection opens, {@link SystemLogHandler} after. Every
 * filter of that path therefore applies unchanged -- excluded loggers, enabled levels, batching,
 * the start-up replay, the same-thread re-entry guard, error reporting. ANSI colour sequences are
 * removed, as Paper's own {@code latest.log} does.
 * <p>
 * <b>No duplicates.</b> Paper also forwards every {@code java.util.logging} record into Log4j,
 * through {@code org.bukkit.craftbukkit.util.ForwardLogHandler} on the root logger, so a plugin
 * line would otherwise arrive twice. The {@code java.util.logging} handler stays the only route
 * for those lines -- it is the one that sees the panel-connection mark
 * ({@code PanelConnectionLog}) and the levels below {@code INFO} -- and the appender skips what
 * Paper's forwarder produces: while installed, each forwarder is wrapped in a handler that marks
 * the current thread for the duration of its own {@code publish}, and Log4j calls the appender on
 * that same thread. Uninstalling puts the original forwarder back.
 * <p>
 * <b>Asynchronous loggers.</b> If the server's Log4j configuration uses asynchronous loggers, the
 * appender runs on another thread, where neither the forwarder mark nor the re-entry guard holds.
 * The mirror is then not installed and a warning says so; the stream carries the plugin lines only,
 * as before 6.3.0.
 * <p>
 * <b>No forwarder, no mirror (fail closed, UltiTools-Reborn#583 F1).</b> The appender can tell a
 * forwarded plugin line from a console line only through the forwarder it wrapped. When no root
 * handler has the binary name {@code org.bukkit.craftbukkit.util.ForwardLogHandler} -- a fork, a build that relocates
 * CraftBukkit, something that replaced the forwarder -- nothing is wrapped, and an appender would
 * deliver every plugin line a second time (and stream panel-connection lines the panel must never
 * get back). The mirror is then not installed: {@link #install()} returns {@code false}, nothing is
 * added to Log4j, and one WARNING per server run says the panel will not mirror the console; the
 * stream carries the plugin lines only, as before 6.3.0.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class ConsoleMirror {

    /** The name of the appender on Log4j's root logger. */
    public static final String APPENDER_NAME = "UltiToolsConsoleMirror";

    /** The binary name of Paper's {@code java.util.logging} to Log4j forwarder. */
    static final String FORWARD_LOG_HANDLER = "org.bukkit.craftbukkit.util.ForwardLogHandler";

    /** An ANSI CSI sequence, such as a colour code. */
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");

    /** How deep the current thread is inside a wrapped forwarder's {@code publish}. */
    private static final ThreadLocal<int[]> FORWARDING = ThreadLocal.withInitial(() -> new int[1]);

    private static final Object LOCK = new Object();

    /** The installed appender, or {@code null}. Guarded by {@link #LOCK}. */
    private static MirrorAppender appender;

    /** The Log4j context the appender is installed in. Guarded by {@link #LOCK}. */
    private static LoggerContext context;

    /**
     * Whether the missing-forwarder WARNING was logged in this server run (#583 F1): {@link
     * #install()} runs again on every panel reconnect, and the outcome cannot change while the
     * server runs. Guarded by {@link #LOCK}.
     */
    private static boolean forwarderMissingWarned;

    private ConsoleMirror() {
    }

    /**
     * Installs the mirror; does nothing when it is already installed. Not installed -- and
     * {@code false} returned -- when the server's Log4j uses asynchronous loggers, or when Paper's
     * forwarder is not on the root logger (fail closed, see the class description).
     *
     * @return whether the mirror is installed afterwards
     */
    public static boolean install() {
        synchronized (LOCK) {
            if (appender != null) {
                return true;
            }
            try {
                return installLocked();
            } catch (RuntimeException | LinkageError e) {
                warn("[UltiPanel] The panel's log stream could not mirror the server console, so it carries "
                        + "plugin lines only: " + e);
                return false;
            }
        }
    }

    private static boolean installLocked() {
        org.apache.logging.log4j.spi.LoggerContext found =
                LogManager.getContext(LogManager.class.getClassLoader(), false);
        if (!(found instanceof LoggerContext)) {
            return false;
        }
        LoggerContext ctx = (LoggerContext) found;
        Configuration configuration = ctx.getConfiguration();
        if (usesAsyncLoggers(ctx, configuration)) {
            warn("[UltiPanel] The server's Log4j configuration uses asynchronous loggers, so the panel's log "
                    + "stream does not mirror the server console and carries plugin lines only.");
            return false;
        }
        if (wrapForwarders() == 0) {
            // Fail closed (#583 F1): nothing was wrapped, so nothing was changed, and the appender
            // could not skip forwarded plugin lines. Warned once per server run.
            if (!forwarderMissingWarned) {
                forwarderMissingWarned = true;
                warn("[UltiPanel] Paper's log forwarder (" + FORWARD_LOG_HANDLER + ") was not found on this "
                        + "server, so the panel will not mirror the server console and its log stream carries "
                        + "plugin lines only.");
            }
            return false;
        }
        MirrorAppender created = new MirrorAppender();
        created.start();
        LoggerConfig root = configuration.getRootLogger();
        // One left by an instance whose disable never ran (another class loader) is replaced.
        root.removeAppender(APPENDER_NAME);
        root.addAppender(created, null, null);
        ctx.updateLoggers();
        appender = created;
        context = ctx;
        return true;
    }

    /**
     * Removes the mirror and puts Paper's forwarder back; does nothing when it is not installed.
     */
    public static void uninstall() {
        synchronized (LOCK) {
            if (appender == null) {
                return;
            }
            try {
                context.getConfiguration().getRootLogger().removeAppender(APPENDER_NAME);
                context.updateLoggers();
                appender.stop();
            } finally {
                unwrapForwarders();
                appender = null;
                context = null;
            }
        }
    }

    /**
     * Whether the mirror is installed.
     *
     * @return whether the mirror is installed
     */
    public static boolean isInstalled() {
        synchronized (LOCK) {
            return appender != null;
        }
    }

    private static boolean usesAsyncLoggers(LoggerContext ctx, Configuration configuration) {
        // Compared by name: loading the async classes needs the LMAX Disruptor, which Paper does
        // not ship.
        if (ctx.getClass().getName().startsWith("org.apache.logging.log4j.core.async.")) {
            return true;
        }
        for (LoggerConfig loggerConfig : configuration.getLoggers().values()) {
            if (loggerConfig.getClass().getName().startsWith("org.apache.logging.log4j.core.async.")) {
                return true;
            }
        }
        return configuration.getRootLogger().getClass().getName()
                .startsWith("org.apache.logging.log4j.core.async.");
    }

    /**
     * Wraps every Paper forwarder on the root logger.
     *
     * @return how many were wrapped; {@code 0} means none was found and nothing was changed
     */
    private static int wrapForwarders() {
        Logger root = Logger.getLogger("");
        int wrapped = 0;
        for (Handler handler : root.getHandlers()) {
            if (FORWARD_LOG_HANDLER.equals(handler.getClass().getName())) {
                root.removeHandler(handler);
                root.addHandler(new ForwardScope(handler));
                wrapped++;
            }
        }
        return wrapped;
    }

    private static void unwrapForwarders() {
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            if (handler instanceof ForwardScope) {
                root.removeHandler(handler);
                root.addHandler(((ForwardScope) handler).delegate);
            }
        }
    }

    /**
     * Hands a converted Log4j event to the stream handler attached to the
     * {@code java.util.logging} root logger, if any.
     */
    static void deliver(LogRecord record) {
        for (Handler handler : Logger.getLogger("").getHandlers()) {
            if (handler instanceof SystemLogHandler || handler instanceof EarlyLogCapture) {
                handler.publish(record);
            }
        }
    }

    /**
     * Converts a Log4j event into the record the stream handler expects.
     */
    @SuppressWarnings("deprecation") // LogRecord#setMillis: the replacement setInstant is Java 9+.
    static LogRecord toRecord(LogEvent event) {
        String message = event.getMessage() == null ? null : event.getMessage().getFormattedMessage();
        LogRecord record = new LogRecord(toJulLevel(event.getLevel()),
                message == null ? "" : ANSI.matcher(message).replaceAll(""));
        record.setLoggerName(event.getLoggerName());
        record.setThrown(event.getThrown());
        record.setMillis(event.getTimeMillis());
        return record;
    }

    private static Level toJulLevel(org.apache.logging.log4j.Level level) {
        int value = level == null ? org.apache.logging.log4j.Level.INFO.intLevel() : level.intLevel();
        if (value <= org.apache.logging.log4j.Level.ERROR.intLevel()) {
            return Level.SEVERE;
        }
        if (value <= org.apache.logging.log4j.Level.WARN.intLevel()) {
            return Level.WARNING;
        }
        if (value <= org.apache.logging.log4j.Level.INFO.intLevel()) {
            return Level.INFO;
        }
        if (value <= org.apache.logging.log4j.Level.DEBUG.intLevel()) {
            return Level.FINE;
        }
        return Level.FINEST;
    }

    private static void warn(String message) {
        UltiTools instance = UltiTools.getInstance();
        Logger logger = instance == null ? null : instance.getLogger();
        if (logger != null) {
            logger.warning(message);
        }
    }

    /** Marks the current thread while Paper's forwarder copies a record into Log4j. */
    private static final class ForwardScope extends Handler {

        private final Handler delegate;

        ForwardScope(Handler delegate) {
            this.delegate = delegate;
            setLevel(Level.ALL);
        }

        @Override
        public void publish(LogRecord record) {
            int[] depth = FORWARDING.get();
            depth[0]++;
            try {
                delegate.publish(record);
            } finally {
                depth[0]--;
            }
        }

        @Override
        public void flush() {
            delegate.flush();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /** The appender on Log4j's root logger. */
    private static final class MirrorAppender extends AbstractAppender {

        MirrorAppender() {
            super(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            if (FORWARDING.get()[0] > 0) {
                // A plugin line Paper is copying into Log4j: the java.util.logging handler has it.
                return;
            }
            deliver(toRecord(event));
        }
    }
}
