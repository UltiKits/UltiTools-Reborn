package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The bounded event outbox (D-11, D-15): capacity, drop-oldest, overflow accounting, ack, snapshots. */
@DisplayName("CloudOutbox -- bounded, drop-oldest, overflow accounted once")
class CloudOutboxTest {

    private final AtomicLong clock = new AtomicLong(1_760_000_000_000L);

    private static JsonObject payload(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("playerName", name);
        return o;
    }

    private static List<String> names(List<CloudOutbox.Entry> entries) {
        return entries.stream().map(e -> e.payload().get("playerName").getAsString()).collect(Collectors.toList());
    }

    private CloudOutbox outboxWith(int capacity, String... names) {
        CloudOutbox outbox = new CloudOutbox(capacity, clock::get);
        for (String name : names) {
            outbox.enqueue("player.join", payload(name));
            clock.incrementAndGet();
        }
        return outbox;
    }

    @Test
    @DisplayName("capacity 3, enqueue 5: the last 3 remain in order and 2 are counted as dropped")
    void fullOutboxDropsTheOldest() {
        CloudOutbox outbox = outboxWith(3, "a", "b", "c", "d", "e");

        assertThat(names(outbox.unacked())).containsExactly("c", "d", "e");
        assertThat(outbox.droppedSinceLastReport()).isEqualTo(2);
    }

    @Test
    @DisplayName("markOverflowReported subtracts what was reported, keeping drops made while the report was in flight")
    void overflowReportKeepsLaterDrops() {
        CloudOutbox outbox = outboxWith(3, "a", "b", "c", "d", "e");
        outbox.markOverflowReported(2);
        assertThat(outbox.droppedSinceLastReport()).isZero();

        CloudOutbox racing = outboxWith(3, "a", "b", "c", "d", "e");
        long reported = racing.droppedSinceLastReport();
        racing.enqueue("player.join", payload("f"));
        racing.markOverflowReported(reported);

        assertThat(reported).isEqualTo(2);
        assertThat(racing.droppedSinceLastReport()).isEqualTo(1);
    }

    @Test
    @DisplayName("lowering the capacity drops the oldest entries at once and counts them")
    void setCapacityShrinksImmediately() {
        CloudOutbox outbox = outboxWith(3, "a", "b", "c");
        outbox.clearDirty();

        outbox.setCapacity(2);

        assertThat(names(outbox.unacked())).containsExactly("b", "c");
        assertThat(outbox.droppedSinceLastReport()).isEqualTo(1);
        assertThat(outbox.isDirty()).isTrue();
        assertThatThrownBy(() -> outbox.setCapacity(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CloudOutbox(0, clock::get)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ack removes known ids; unknown ids return 0 and leave the dirty flag unchanged")
    void ackRemovesOnlyKnownIds() {
        CloudOutbox outbox = outboxWith(5, "a", "b", "c");
        List<CloudOutbox.Entry> entries = outbox.unacked();
        outbox.clearDirty();

        assertThat(outbox.ack(Arrays.asList("01ARYZ6S41TSV4RRFFQ69G5FAV", "unknown"))).isZero();
        assertThat(outbox.isDirty()).isFalse();

        assertThat(outbox.ack(Arrays.asList(entries.get(0).id(), entries.get(2).id()))).isEqualTo(2);
        assertThat(outbox.isDirty()).isTrue();
        assertThat(names(outbox.unacked())).containsExactly("b");
    }

    @Test
    @DisplayName("every enqueued entry gets a fresh ULID and the clock's time")
    void enqueueAssignsUlidAndTime() {
        CloudOutbox outbox = new CloudOutbox(CloudOutbox.DEFAULT_CAPACITY, clock::get);
        CloudOutbox.Entry first = outbox.enqueue("player.join", payload("a"));
        CloudOutbox.Entry second = outbox.enqueue("player.leave", payload("a"));

        assertThat(Ulid.isValid(first.id())).isTrue();
        assertThat(second.id()).isGreaterThan(first.id());
        assertThat(first.ts()).isEqualTo(clock.get());
        assertThat(second.type()).isEqualTo("player.leave");
        assertThat(CloudOutbox.DEFAULT_CAPACITY).isEqualTo(1000);
    }

    @Test
    @DisplayName("unacked() is a snapshot unaffected by later mutation")
    void unackedIsASnapshot() {
        CloudOutbox outbox = outboxWith(5, "a", "b");
        List<CloudOutbox.Entry> snapshot = outbox.unacked();

        outbox.enqueue("player.join", payload("c"));
        outbox.ack(Collections.singletonList(snapshot.get(0).id()));

        assertThat(names(snapshot)).containsExactly("a", "b");
    }

    @Test
    @DisplayName("payloads are copied in and out")
    void payloadsAreDefensiveCopies() {
        CloudOutbox outbox = new CloudOutbox(5, clock::get);
        JsonObject given = payload("a");
        CloudOutbox.Entry entry = outbox.enqueue("player.join", given);

        given.addProperty("playerName", "changed-by-caller");
        entry.payload().addProperty("playerName", "changed-by-reader");

        assertThat(outbox.unacked().get(0).payload().get("playerName").getAsString()).isEqualTo("a");
    }

    @Test
    @DisplayName("clear empties the outbox, resets the dropped count and marks it dirty")
    void clearResetsEverything() {
        CloudOutbox outbox = outboxWith(2, "a", "b", "c");
        outbox.clearDirty();

        outbox.clear();

        assertThat(outbox.unacked()).isEmpty();
        assertThat(outbox.droppedSinceLastReport()).isZero();
        assertThat(outbox.isDirty()).isTrue();
    }

    @Test
    @DisplayName("restore keeps the newest entries within capacity and counts the rest; a clean load is not dirty")
    void restoreAppliesTheCapacity() {
        List<CloudOutbox.Entry> loaded = outboxWith(10, "a", "b", "c", "d").unacked();

        CloudOutbox small = new CloudOutbox(2, clock::get);
        small.restore(loaded, 5);
        assertThat(names(small.unacked())).containsExactly("c", "d");
        assertThat(small.droppedSinceLastReport()).isEqualTo(7);
        assertThat(small.isDirty()).isTrue();

        CloudOutbox large = new CloudOutbox(10, clock::get);
        large.restore(loaded, 5);
        assertThat(names(large.unacked())).containsExactly("a", "b", "c", "d");
        assertThat(large.isDirty()).isFalse();
    }

    @Test
    @DisplayName("a mutation after a snapshot keeps the dirty flag set when the snapshot's write completes")
    void clearDirtyIfUnchangedKeepsRacingMutations() {
        CloudOutbox outbox = outboxWith(5, "a");
        CloudOutbox.Snapshot snapshot = outbox.snapshot();
        outbox.enqueue("player.join", payload("b"));

        outbox.clearDirtyIfUnchanged(snapshot.modCount());
        assertThat(outbox.isDirty()).isTrue();

        outbox.clearDirtyIfUnchanged(outbox.snapshot().modCount());
        assertThat(outbox.isDirty()).isFalse();
    }
}
