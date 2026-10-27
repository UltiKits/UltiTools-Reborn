package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractSchemas;

/**
 * Proves that {@link OutboxSeedTool} gives the outbox-overflow UAT row a defined way to reach 1000
 * queued events (plan 22-19): the file it writes is the framework's own, owner-only, and loads back
 * through {@link CloudStateStore} with every event intact and schema-valid.
 */
class OutboxSeedToolTest {

    private static final String SERVER_ID = "01JB2Z8Y3K4M5N6P7Q8R9S0SRV";

    @TempDir
    Path root;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void seedsAThousandSchemaValidJoinsThatTheFrameworkLoadsBackWhole() throws Exception {
        assertThat(run("--server-root", root.toString(), "--server-id", SERVER_ID, "--count", "1000")).isZero();

        Path file = root.resolve(".ultikits").resolve(CloudStateStore.FILE_NAME);
        assertThat(file).exists();
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        }
        CloudOutbox outbox = new CloudOutbox(CloudOutbox.DEFAULT_CAPACITY, System::currentTimeMillis);
        CloudStateStore store = new CloudStateStore(file, outbox, new CommandDedupeSet(
                CommandDedupeSet.DEFAULT_DEDUPE_MAX, CommandDedupeSet.DEFAULT_DEDUPE_TTL_MS, System::currentTimeMillis));
        store.setLoggerForTesting(java.util.logging.Logger.getAnonymousLogger());
        store.load(SERVER_ID);

        List<CloudOutbox.Entry> entries = outbox.unacked();
        assertThat(entries).hasSize(1000);
        assertThat(outbox.droppedSinceLastReport()).isZero();
        Set<String> players = new HashSet<>();
        for (CloudOutbox.Entry entry : entries) {
            assertThat(entry.type()).isEqualTo("player.join");
            JsonObject frame = new JsonObject();
            frame.addProperty("v", 2);
            frame.addProperty("id", entry.id());
            frame.addProperty("type", entry.type());
            frame.addProperty("ts", entry.ts());
            frame.add("payload", entry.payload());
            ContractSchemas.assertFrameValid(frame);
            players.add(entry.payload().get("playerUuid").getAsString());
        }
        assertThat(players).as("every seeded player is distinct").hasSize(1000);
        assertThat(out.toString("UTF-8")).contains("1000").contains(file.toString());
    }

    @Test
    void refusesToOverwriteAnExistingStateFileUnlessForced() throws Exception {
        assertThat(run("--server-root", root.toString(), "--server-id", SERVER_ID, "--count", "3")).isZero();
        Path file = root.resolve(".ultikits").resolve(CloudStateStore.FILE_NAME);
        byte[] before = Files.readAllBytes(file);

        assertThat(run("--server-root", root.toString(), "--server-id", SERVER_ID, "--count", "5")).isNotZero();
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(err.toString("UTF-8")).contains("--force");

        assertThat(run("--server-root", root.toString(), "--server-id", SERVER_ID, "--count", "5", "--force")).isZero();
        CloudOutbox outbox = new CloudOutbox(CloudOutbox.DEFAULT_CAPACITY, System::currentTimeMillis);
        CloudStateStore store = new CloudStateStore(file, outbox, new CommandDedupeSet(
                CommandDedupeSet.DEFAULT_DEDUPE_MAX, CommandDedupeSet.DEFAULT_DEDUPE_TTL_MS, System::currentTimeMillis));
        store.setLoggerForTesting(java.util.logging.Logger.getAnonymousLogger());
        store.load(SERVER_ID);
        assertThat(outbox.unacked()).hasSize(5);
    }

    @Test
    void refusesArgumentsThatCannotDescribeTheRow() throws Exception {
        assertThat(run("--server-root", root.toString(), "--count", "10")).as("no server id").isEqualTo(2);
        assertThat(run("--server-root", root.toString(), "--server-id", "abc", "--count", "10")).isEqualTo(2);
        assertThat(run("--server-root", root.toString(), "--server-id", SERVER_ID, "--count", "0")).isEqualTo(2);
        assertThat(run("--server-root", root.toString(), "--server-id", SERVER_ID, "--count", "1001"))
                .as("more than the outbox holds").isEqualTo(2);
        assertThat(run("--server-id", SERVER_ID)).as("no server root").isEqualTo(2);
        assertThat(run("--bogus")).isEqualTo(2);
        assertThat(root.resolve(".ultikits")).doesNotExist();
    }

    @Test
    void helpPrintsTheUsage() throws Exception {
        assertThat(run("--help")).isZero();
        assertThat(out.toString("UTF-8")).contains("--server-root").contains("--server-id").contains("--count")
                .contains("--force").contains("com.ultikits.ultitools.utils.OutboxSeedTool");
    }

    private int run(String... args) {
        out.reset();
        err.reset();
        return OutboxSeedTool.run(args, new PrintStream(out, true), new PrintStream(err, true));
    }
}
