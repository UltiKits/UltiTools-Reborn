package com.ultikits.ultitools.utils;

import java.security.SecureRandom;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * ULID generation and validation for UltiCloud protocol v2 envelope ids (D-11, D-21): 26 Crockford base32
 * characters ({@code 0123456789ABCDEFGHJKMNPQRSTVWXYZ}), a 48-bit millisecond timestamp followed by 80 random bits,
 * as in the ULID specification (https://github.com/ulid/spec).
 * <p>
 * <b>Monotonic per generator.</b> Within one millisecond -- or when the clock steps backwards -- a generator keeps
 * the last timestamp and increments the 80-bit random part by one, so ids from one generator are strictly
 * increasing in both time order and string order. If the random part would overflow, the generator waits for the
 * clock to pass the last timestamp and draws fresh randomness.
 * <p>
 * <b>Thread safety.</b> {@link #next()} draws from one shared generator whose {@link #nextValue()} is
 * {@code synchronized}: envelope ids are drawn on the main thread (outbox enqueue) and on the socket and scheduler
 * threads (outgoing envelopes), and every id must be unique across all of them.
 */
final class Ulid {

    /** The length of a ULID string. */
    static final int LENGTH = 26;

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int TIME_CHARS = 10;
    private static final long MAX_TIME = (1L << 48) - 1;
    private static final long HIGH_MASK = 0xFFFFL;
    private static final int[] DECODE = new int[128];

    static {
        for (int i = 0; i < DECODE.length; i++) {
            DECODE[i] = -1;
        }
        for (int i = 0; i < ALPHABET.length; i++) {
            DECODE[ALPHABET[i]] = i;
            DECODE[Character.toLowerCase(ALPHABET[i])] = i;
        }
    }

    private static final Ulid SHARED = new Ulid(System::currentTimeMillis, new SecureRandom());

    private final LongSupplier clock;
    private final Random random;

    /** The timestamp of the last id, or {@code -1} before the first. Guarded by {@code this}. */
    private long lastTime = -1L;
    /** The top 16 of the 80 random bits of the last id. Guarded by {@code this}. */
    private long randomHigh;
    /** The low 64 of the 80 random bits of the last id. Guarded by {@code this}. */
    private long randomLow;

    /**
     * A generator with its own clock and randomness; tests use this to freeze time. Production uses {@link #next()}.
     *
     * @param clock  milliseconds since the Unix epoch
     * @param random the source of the 80 random bits
     */
    Ulid(LongSupplier clock, Random random) {
        this.clock = clock;
        this.random = random;
    }

    /**
     * @return a new ULID from the shared, synchronised generator
     */
    static String next() {
        return SHARED.nextValue();
    }

    /**
     * @return the next ULID of this generator, strictly greater than every earlier one it produced
     * @throws IllegalStateException if the clock is outside the 48-bit ULID time range
     */
    synchronized String nextValue() {
        long now = checkedTime(clock.getAsLong());
        if (now > lastTime) {
            lastTime = now;
            drawRandom();
        } else if (!incrementRandom()) {
            // The 80-bit random part overflowed within one millisecond: wait for the next one.
            long later = now;
            while (later <= lastTime) {
                Thread.yield();
                later = checkedTime(clock.getAsLong());
            }
            lastTime = later;
            drawRandom();
        }
        return encode(lastTime, randomHigh, randomLow);
    }

    private static long checkedTime(long time) {
        if (time < 0 || time > MAX_TIME) {
            throw new IllegalStateException("Clock value " + time + " is outside the ULID time range");
        }
        return time;
    }

    private void drawRandom() {
        randomHigh = random.nextInt() & HIGH_MASK;
        randomLow = random.nextLong();
    }

    /** @return {@code false} when the 80-bit value wrapped to zero */
    private boolean incrementRandom() {
        randomLow++;
        if (randomLow != 0) {
            return true;
        }
        randomHigh = (randomHigh + 1) & HIGH_MASK;
        return randomHigh != 0;
    }

    private static String encode(long time, long high, long low) {
        char[] out = new char[LENGTH];
        for (int i = 0; i < TIME_CHARS; i++) {
            out[i] = ALPHABET[(int) ((time >>> (5 * (TIME_CHARS - 1 - i))) & 31)];
        }
        for (int j = 0; j < LENGTH - TIME_CHARS; j++) {
            int shift = 75 - 5 * j;
            long chunk;
            if (shift >= 64) {
                chunk = high >>> (shift - 64);
            } else if (shift + 5 <= 64) {
                chunk = low >>> shift;
            } else {
                chunk = (low >>> shift) | (high << (64 - shift));
            }
            out[TIME_CHARS + j] = ALPHABET[(int) (chunk & 31)];
        }
        return new String(out);
    }

    /**
     * Whether {@code value} is a ULID: 26 Crockford base32 characters, case-insensitive (the specification's
     * decoding rule), without {@code I}, {@code L}, {@code O} or {@code U}, and within the 128-bit range (the first
     * character is at most {@code 7}).
     *
     * @param value the candidate, may be {@code null}
     * @return {@code true} if it is a ULID
     */
    static boolean isValid(String value) {
        if (value == null || value.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            char c = value.charAt(i);
            if (c >= DECODE.length || DECODE[c] < 0) {
                return false;
            }
        }
        return DECODE[value.charAt(0)] <= 7;
    }

    /**
     * @param value a ULID
     * @return its timestamp in milliseconds since the Unix epoch
     * @throws IllegalArgumentException if {@code value} is not a ULID
     */
    static long timestampOf(String value) {
        if (!isValid(value)) {
            throw new IllegalArgumentException("Not a ULID");
        }
        long time = 0;
        for (int i = 0; i < TIME_CHARS; i++) {
            time = (time << 5) | DECODE[value.charAt(i)];
        }
        return time;
    }
}
