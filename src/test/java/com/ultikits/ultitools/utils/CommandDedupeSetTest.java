package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The command-dedupe set (D-17): the last N ids or the TTL, whichever bound is reached first. */
@DisplayName("CommandDedupeSet -- first seen once per id within 1000 ids and 24 h")
class CommandDedupeSetTest {

    private static final long HOUR = 3_600_000L;

    private final AtomicLong clock = new AtomicLong(1_760_000_000_000L);

    @Test
    @DisplayName("the same id twice is first-seen, then a repeat")
    void sameIdTwice() {
        CommandDedupeSet set = new CommandDedupeSet(CommandDedupeSet.DEFAULT_DEDUPE_MAX,
                CommandDedupeSet.DEFAULT_DEDUPE_TTL_MS, clock::get);

        assertThat(set.firstSeen("a")).isTrue();
        assertThat(set.firstSeen("a")).isFalse();
        assertThat(CommandDedupeSet.DEFAULT_DEDUPE_MAX).isEqualTo(1000);
        assertThat(CommandDedupeSet.DEFAULT_DEDUPE_TTL_MS).isEqualTo(24 * HOUR);
    }

    @Test
    @DisplayName("with max 3, ids a b c d evict a, so a is first-seen again")
    void countBoundEvictsTheOldest() {
        CommandDedupeSet set = new CommandDedupeSet(3, 24 * HOUR, clock::get);
        for (String id : Arrays.asList("a", "b", "c", "d")) {
            assertThat(set.firstSeen(id)).isTrue();
        }

        assertThat(set.firstSeen("a")).as("a was evicted").isTrue();
        assertThat(set.firstSeen("d")).as("d is still remembered").isFalse();
    }

    @Test
    @DisplayName("an id seen exactly 24 h ago is a repeat; 24 h + 1 ms ago it is first-seen again")
    void ttlBoundExpiresOldIds() {
        CommandDedupeSet set = new CommandDedupeSet(1000, 24 * HOUR, clock::get);
        set.firstSeen("a");
        set.firstSeen("b");

        clock.addAndGet(24 * HOUR);
        assertThat(set.firstSeen("a")).as("exactly the TTL").isFalse();

        clock.addAndGet(1);
        assertThat(set.firstSeen("b")).as("TTL + 1 ms").isTrue();
    }

    @Test
    @DisplayName("whichever bound is reached first wins")
    void firstBoundWins() {
        CommandDedupeSet byCount = new CommandDedupeSet(2, 24 * HOUR, clock::get);
        byCount.firstSeen("a");
        byCount.firstSeen("b");
        byCount.firstSeen("c");
        assertThat(byCount.firstSeen("a")).as("count bound reached long before the TTL").isTrue();

        CommandDedupeSet byTime = new CommandDedupeSet(1000, HOUR, clock::get);
        byTime.firstSeen("x");
        clock.addAndGet(HOUR + 1);
        assertThat(byTime.firstSeen("x")).as("TTL reached long before the count bound").isTrue();
    }

    @Test
    @DisplayName("setLimits shrinks immediately, by count and by TTL")
    void setLimitsShrinksImmediately() {
        CommandDedupeSet set = new CommandDedupeSet(10, 24 * HOUR, clock::get);
        set.firstSeen("a");
        clock.addAndGet(2 * HOUR);
        set.firstSeen("b");
        set.firstSeen("c");
        set.firstSeen("d");
        set.clearDirty();

        set.setLimits(2, 24 * HOUR);
        assertThat(ids(set.snapshot())).containsExactly("c", "d");
        assertThat(set.isDirty()).isTrue();

        CommandDedupeSet timed = new CommandDedupeSet(10, 24 * HOUR, clock::get);
        timed.firstSeen("old");
        clock.addAndGet(2 * HOUR);
        timed.firstSeen("new");
        timed.setLimits(10, HOUR);
        assertThat(ids(timed.snapshot())).containsExactly("new");

        assertThatThrownBy(() -> set.setLimits(0, HOUR)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> set.setLimits(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("restore(snapshot()) reproduces the state; a clean restore is not dirty")
    void restoreReproducesTheSnapshot() {
        CommandDedupeSet set = new CommandDedupeSet(5, 24 * HOUR, clock::get);
        set.firstSeen("a");
        clock.incrementAndGet();
        set.firstSeen("b");
        List<CommandDedupeSet.Seen> snapshot = set.snapshot();

        CommandDedupeSet copy = new CommandDedupeSet(5, 24 * HOUR, clock::get);
        copy.restore(snapshot);

        assertThat(ids(copy.snapshot())).containsExactly("a", "b");
        assertThat(copy.snapshot().get(0).seenAt()).isEqualTo(snapshot.get(0).seenAt());
        assertThat(copy.isDirty()).isFalse();
        assertThat(copy.firstSeen("a")).isFalse();
        assertThat(copy.firstSeen("z")).isTrue();
    }

    @Test
    @DisplayName("restore applies both bounds to loaded ids and then marks the set dirty")
    void restoreAppliesBounds() {
        CommandDedupeSet set = new CommandDedupeSet(2, HOUR, clock::get);
        long now = clock.get();
        set.restore(Arrays.asList(new CommandDedupeSet.Seen("expired", now - HOUR - 1),
                new CommandDedupeSet.Seen("a", now), new CommandDedupeSet.Seen("b", now),
                new CommandDedupeSet.Seen("c", now)));

        assertThat(ids(set.snapshot())).containsExactly("b", "c");
        assertThat(set.isDirty()).isTrue();
    }

    @Test
    @DisplayName("clear forgets every id and marks the set dirty")
    void clearForgetsEverything() {
        CommandDedupeSet set = new CommandDedupeSet(5, HOUR, clock::get);
        set.firstSeen("a");
        set.clearDirty();

        set.clear();

        assertThat(set.snapshot()).isEmpty();
        assertThat(set.isDirty()).isTrue();
        assertThat(set.firstSeen("a")).isTrue();
    }

    @Test
    @DisplayName("a repeat changes nothing and leaves the set clean; a racing mutation keeps it dirty")
    void dirtyTracking() {
        CommandDedupeSet set = new CommandDedupeSet(5, HOUR, clock::get);
        set.firstSeen("a");
        set.clearDirty();

        set.firstSeen("a");
        assertThat(set.isDirty()).isFalse();

        long mod = set.modCount();
        set.firstSeen("b");
        set.clearDirtyIfUnchanged(mod);
        assertThat(set.isDirty()).isTrue();
        set.clearDirtyIfUnchanged(set.modCount());
        assertThat(set.isDirty()).isFalse();
    }

    private static List<String> ids(List<CommandDedupeSet.Seen> seen) {
        return seen.stream().map(CommandDedupeSet.Seen::id).collect(Collectors.toList());
    }
}
