package com.ultikits.ultitools.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple rate limiter for UltiCloud API calls.
 * Prevents excessive API calls during crash loops or rapid restarts.
 * <br>
 * UltiCloud API调用的简单速率限制器。
 * 防止崩溃循环或快速重启期间的过多API调用。
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
     * @return remaining seconds, or 0 if no cooldown active
     */
    public static long getRemainingCooldown(String key, long cooldownMs) {
        Long lastCall = lastCallTimestamps.get(key);
        if (lastCall == null) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - lastCall;
        long remaining = cooldownMs - elapsed;
        return remaining > 0 ? remaining / 1000 : 0;
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
