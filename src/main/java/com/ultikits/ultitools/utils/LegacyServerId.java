package com.ultikits.ultitools.utils;

import java.util.Locale;

/**
 * Normalises this server's stored install id ({@code uuid} in {@code credentials.json}) into the
 * {@code attachToServerId} value of the device authorization request.
 *
 * @since 6.3.1
 */
final class LegacyServerId {

    private static final int HEX_LENGTH = 32;

    private LegacyServerId() {
    }

    /**
     * @param raw the stored value, or {@code null}
     * @return 32 lower-case hex digits, or {@code null} when {@code raw} does not normalise to them
     */
    static String canonical(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return isHex(value, HEX_LENGTH) ? value : null;
    }

    private static boolean isHex(String value, int length) {
        if (value.length() != length) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!isAsciiHex(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** ASCII only: {@link Character#digit(char, int)} would also accept non-ASCII digits. */
    private static boolean isAsciiHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
