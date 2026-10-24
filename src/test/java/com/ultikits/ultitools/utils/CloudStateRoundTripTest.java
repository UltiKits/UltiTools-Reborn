package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tracer for the reliable-delivery state (D-11, D-15, D-17, S-15): an event enqueued in the outbox and a command
 * id marked seen survive a flush to {@code cloud-outbox.json} and a load into brand-new instances, an ack removes the
 * event, and the removal reaches the file on the next flush.
 */
@DisplayName("cloud-outbox.json round trip: enqueue, flush, load in a new store, ack, flush")
class CloudStateRoundTripTest {

    private static final String SERVER_ID = "S1";
    private static final String COMMAND_ID = "01JAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String PLAYER_UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";

    @TempDir
    Path tempDir;

    private final AtomicLong clock = new AtomicLong(1_760_000_000_000L);

    private CloudStateStore newStore(Path file) {
        CloudOutbox outbox = new CloudOutbox(CloudOutbox.DEFAULT_CAPACITY, clock::get);
        CommandDedupeSet dedupe = new CommandDedupeSet(CommandDedupeSet.DEFAULT_DEDUPE_MAX,
                CommandDedupeSet.DEFAULT_DEDUPE_TTL_MS, clock::get);
        return new CloudStateStore(file, outbox, dedupe);
    }

    private static JsonObject joinPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("playerUuid", PLAYER_UUID);
        payload.addProperty("playerName", "Steve");
        return payload;
    }

    @Test
    @DisplayName("an event and a seen command id come back from a new store; an ack is persisted by the next flush")
    void eventAndCommandIdSurviveARestartAndAnAckIsPersisted() throws Exception {
        Path file = tempDir.resolve(".ultikits").resolve(CloudStateStore.FILE_NAME);
        CloudStateStore first = newStore(file);
        CloudOutbox.Entry enqueued = first.outbox().enqueue("player.join", joinPayload());
        assertThat(first.dedupe().firstSeen(COMMAND_ID)).isTrue();

        first.flush(SERVER_ID);

        assertThat(Files.exists(file)).isTrue();
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        }

        CloudStateStore second = newStore(file);
        second.load(SERVER_ID);
        List<CloudOutbox.Entry> loaded = second.outbox().unacked();
        assertThat(loaded).hasSize(1);
        CloudOutbox.Entry entry = loaded.get(0);
        assertThat(entry.id()).isEqualTo(enqueued.id());
        assertThat(Ulid.isValid(entry.id())).isTrue();
        assertThat(entry.type()).isEqualTo("player.join");
        assertThat(entry.ts()).isEqualTo(enqueued.ts()).isEqualTo(clock.get());
        assertThat(entry.payload()).isEqualTo(joinPayload());
        assertThat(second.dedupe().firstSeen(COMMAND_ID)).as("a persisted command id is not first-seen again").isFalse();

        assertThat(second.outbox().ack(Collections.singletonList(entry.id()))).isEqualTo(1);
        assertThat(second.flushIfDirty(SERVER_ID)).isTrue();

        CloudStateStore third = newStore(file);
        third.load(SERVER_ID);
        assertThat(third.outbox().unacked()).isEmpty();

        FileTime before = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(file, before);
        assertThat(third.flushIfDirty(SERVER_ID)).as("nothing changed since the load").isFalse();
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(before);
    }
}
