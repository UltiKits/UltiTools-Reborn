package com.ultikits.ultitools.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple rate limiter for UltiCloud API calls.
 * Prevents excessive API calls during crash loops or rapid restarts.
 */
public class ApiRateLimiter {

    private static final Map<String, Long> lastCallTimestamps = new ConcurrentHashMap<>();
    private static final long DEFAULT_COOLDOWN_MS = 60_000; // 1 minute default
    private static final long LOGIN_COOLDOWN_MS = 60_000;   // 1 minute for login attempts

    /**
     * Check if an API call is allowed. Returns true if enough time has passed
     * since the last call with the same key.
     *
     * @param key unique identifier for the API call type (e.g., "login", "register")
     * @param cooldownMs minimum time between calls in milliseconds
     * @return true if the call is allowed, false if rate limited
     */
    public static boolean isAllowed(String key, long cooldownMs) {
        long now = System.currentTimeMillis();
        Long lastCall = lastCallTimestamps.get(key);
        if (lastCall != null && (now - lastCall) < cooldownMs) {
            return false;
        }
        lastCallTimestamps.put(key, now);
        return true;
    }

    /**
     * Check if an API call is allowed using the default cooldown.
     */
    public static boolean isAllowed(String key) {
        return isAllowed(key, DEFAULT_COOLDOWN_MS);
    }

    /**
     * Check if a login attempt is allowed (uses longer cooldown).
     */
    public static boolean isLoginAllowed() {
        return isAllowed("login", LOGIN_COOLDOWN_MS);
    }

    /**
     * Get remaining cooldown time in seconds for a given key.
     *
     * @param key the API call type key
     * @param cooldownMs the cooldown period in milliseconds
     * @return remaining seconds, rounded up, or 0 if no cooldown active
     */
    public static long getRemainingCooldown(String key, long cooldownMs) {
        Long lastCall = lastCallTimestamps.get(key);
        if (lastCall == null) {
            return 0;
        }
        return remainingSeconds(lastCall, cooldownMs, System.currentTimeMillis());
    }

    /**
     * Whole seconds left of a cooldown, rounded up: 9.9 s left is shown as 10, and any part of a
     * second left as 1, so a caller that is still refused never reads "0 seconds" (#753).
     *
     * @param lastCall   when the cooldown started, in epoch milliseconds
     * @param cooldownMs the cooldown period in milliseconds
     * @param now        the current time in epoch milliseconds
     * @return the remaining seconds rounded up, or 0 once the cooldown has passed
     */
    static long remainingSeconds(long lastCall, long cooldownMs, long now) {
        long remaining = cooldownMs - (now - lastCall);
        return remaining > 0 ? (remaining + 999L) / 1000L : 0L;
    }

    /**
     * Reset the rate limiter for a specific key (e.g., after successful login).
     */
    public static void reset(String key) {
        lastCallTimestamps.remove(key);
    }

    /**
     * Reset all rate limits.
     */
    public static void resetAll() {
        lastCallTimestamps.clear();
    }
}
