package com.ultikits.ultitools.utils;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.google.gson.JsonObject;

/**
 * Writes a {@code <server root>/.ultikits/cloud-outbox.json} holding N queued {@code player.join}
 * events, so the outbox-overflow UAT row can reach the outbox's cap of 1000 without 1000 real
 * joins (plan 22-19, checker blocker B-B4).
 *
 * <p>It writes through the framework's own {@link CloudStateStore}, {@link CloudOutbox} and
 * {@link CommandDedupeSet}, so the file is exactly what the framework writes and reads: owner-only,
 * atomically replaced, version 1. Each event gets a ULID id, the current time and a schema-valid
 * {@code player.join} payload with a synthetic player ({@code SeedPlayer<i>} and a name-based UUID).
 *
 * <p><b>Why this write is allowed (D-31).</b> The state file is framework-owned, replayable state,
 * not operator configuration (see {@link CloudStateStore}). The tool is UAT setup run while the
 * server is stopped, and it refuses to replace an existing state file unless {@code --force} is
 * given, so it never discards buffered events by accident.
 *
 * <p>Test scope only. Run it from the framework checkout's root with the classpath of
 * {@code com.ultikits.ultitools.stub.UltiCloudStub#CLASSPATH_COMMAND}:
 * {@code java -cp "target/test-classes:target/classes:$(cat target/stub.classpath)"
 * com.ultikits.ultitools.utils.OutboxSeedTool --server-root <dir> --server-id <ULID> --count 1000}.
 *
 * @since 6.3.1
 */
public final class OutboxSeedTool {

    /** Exit code for arguments that cannot describe a seed. */
    static final int BAD_ARGUMENTS = 2;
    /** Exit code when a state file exists and {@code --force} was not given. */
    static final int EXISTS = 3;
    /** Exit code when the file could not be written. */
    static final int WRITE_FAILED = 1;

    private OutboxSeedTool() {
    }

    /**
     * @param args {@code --server-root DIR --server-id ULID [--count N] [--force]}, or {@code --help}
     */
    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    /**
     * The body of {@link #main(String[])}, returning the exit code instead of exiting.
     *
     * @param args the program arguments
     * @param out  where the result line and the help go
     * @param err  where errors go
     * @return 0 on success or {@code --help}; {@value #BAD_ARGUMENTS}, {@value #EXISTS} or
     *         {@value #WRITE_FAILED} otherwise
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        Path serverRoot = null;
        String serverId = null;
        int count = CloudOutbox.DEFAULT_CAPACITY;
        boolean force = false;
        try {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    out.print(usage());
                    out.flush();
                    return 0;
                } else if ("--server-root".equals(arg)) {
                    serverRoot = Paths.get(value(args, ++i, arg));
                } else if ("--server-id".equals(arg)) {
                    serverId = value(args, ++i, arg);
                } else if ("--count".equals(arg)) {
                    count = count(value(args, ++i, arg));
                } else if ("--force".equals(arg)) {
                    force = true;
                } else {
                    throw new IllegalArgumentException("unknown argument " + arg);
                }
            }
            if (serverRoot == null) {
                throw new IllegalArgumentException("--server-root is required");
            }
            if (serverId == null || !Ulid.isValid(serverId)) {
                throw new IllegalArgumentException("--server-id must be the linked server's id, a ULID");
            }
        } catch (IllegalArgumentException bad) {
            err.println("OutboxSeedTool: " + bad.getMessage());
            err.print(usage());
            return BAD_ARGUMENTS;
        }
        Path file = serverRoot.resolve(".ultikits").resolve(CloudStateStore.FILE_NAME).toAbsolutePath();
        if (Files.exists(file) && !force) {
            err.println("OutboxSeedTool: " + file + " exists; refusing to replace it (pass --force to replace it).");
            return EXISTS;
        }
        CloudOutbox outbox = new CloudOutbox(CloudOutbox.DEFAULT_CAPACITY, System::currentTimeMillis);
        CommandDedupeSet dedupe = new CommandDedupeSet(CommandDedupeSet.DEFAULT_DEDUPE_MAX,
                CommandDedupeSet.DEFAULT_DEDUPE_TTL_MS, System::currentTimeMillis);
        CloudStateStore store = new CloudStateStore(file, outbox, dedupe);
        store.setLoggerForTesting(loggerTo(err));
        for (int i = 0; i < count; i++) {
            JsonObject payload = new JsonObject();
            payload.addProperty("playerUuid", UUID.nameUUIDFromBytes(
                    ("ultitools-outbox-seed-" + i).getBytes(StandardCharsets.UTF_8)).toString());
            payload.addProperty("playerName", "SeedPlayer" + i);
            outbox.enqueue("player.join", payload);
        }
        try {
            store.flush(serverId);
        } catch (RuntimeException failure) {
            err.println("OutboxSeedTool: could not write " + file + ": " + failure.getMessage());
            return WRITE_FAILED;
        }
        out.println("OutboxSeedTool: wrote " + count + " queued player.join events for server " + serverId
                + " to " + file + " (owner-only).");
        out.flush();
        return 0;
    }

    static String usage() {
        return "OutboxSeedTool - queue N player.join events in <server root>/.ultikits/cloud-outbox.json\n\n"
                + "Run while the server is stopped, from the framework checkout's root:\n"
                + "  java -cp \"target/test-classes:target/classes:$(cat target/stub.classpath)\" "
                + "com.ultikits.ultitools.utils.OutboxSeedTool --server-root <dir> --server-id <ULID> --count 1000\n\n"
                + "Options:\n"
                + "  --server-root DIR  the server's root directory (the one holding .ultikits/)\n"
                + "  --server-id ULID   the linked server's id (cloud_credential's serverId); another id is discarded\n"
                + "                     by the framework at load\n"
                + "  --count N          events to queue, 1 to " + CloudOutbox.DEFAULT_CAPACITY + " (default "
                + CloudOutbox.DEFAULT_CAPACITY + ")\n"
                + "  --force            replace an existing cloud-outbox.json\n"
                + "  --help             print this text\n";
    }

    private static String value(String[] args, int index, String name) {
        if (index >= args.length) {
            throw new IllegalArgumentException(name + " needs a value");
        }
        return args[index];
    }

    private static int count(String value) {
        try {
            int count = Integer.parseInt(value);
            if (count >= 1 && count <= CloudOutbox.DEFAULT_CAPACITY) {
                return count;
            }
        } catch (NumberFormatException notANumber) {
            // Answered below.
        }
        throw new IllegalArgumentException("--count must be a number from 1 to " + CloudOutbox.DEFAULT_CAPACITY
                + " (the outbox's capacity)");
    }

    private static Logger loggerTo(PrintStream err) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                err.println("OutboxSeedTool: " + record.getLevel() + " " + record.getMessage());
            }

            @Override
            public void flush() {
                err.flush();
            }

            @Override
            public void close() {
                // The stream belongs to the caller.
            }
        });
        return logger;
    }
}
