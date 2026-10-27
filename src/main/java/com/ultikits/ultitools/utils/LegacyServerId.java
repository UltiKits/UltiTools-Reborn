package com.ultikits.ultitools.utils;

/**
 * Normalises this server's stored install id ({@code uuid} in {@code credentials.json}) into the
 * {@code legacyServerUuid} value of the device authorization request ({@code attachToServerId}
 * is the panel's field on {@code /v1/device/approve}, never sent by the plugin).
 * <p>
 * There is no automatic migration of old servers (maintainer decision 31): the owner re-attaches
 * this server's old record on the confirmation page, and UltiCloud finds that record by this value.
 * {@link #canonical(String)} must therefore equal the backend's {@code canonicalLegacyUuid} (backend
 * schema decision 22) exactly; a change to either side is made on both.
 * <p>
 * The decision text lists "remove hyphens" before "the first 36 characters form a hyphenated UUID",
 * which cannot both hold; this class applies the only order that makes sense -- cut a longer value to
 * its leading 36-character UUID first, then remove the hyphens (Phase 22 decision S-41). The
 * backend's golden fixture {@code legacy-uuid-canonicalization.json} (contract 2.0.0) confirms that
 * order, and {@code LegacyServerIdTest} runs every one of its cases.
 *
 * @since 6.3.1
 */
final class LegacyServerId {

    private static final int HEX_LENGTH = 32;
    private static final int HYPHENATED_LENGTH = 36;

    private LegacyServerId() {
    }

    /**
     * The backend's {@code canonicalLegacyUuid} rule: trim surrounding whitespace; if what remains is
     * longer than 36 characters and its first 36 characters form a standard hyphenated UUID
     * (8-4-4-4-12 hex digits, either case), keep only those 36; remove every {@code -}; lower-case
     * ASCII {@code A-F}; return the result only if it is exactly 32 hex digits. Trimming is
     * {@link String#trim()} (U+0000..U+0020 only), as the backend fixture states.
     *
     * @param raw the stored value, or {@code null}
     * @return 32 lower-case hex digits, or {@code null} when {@code raw} does not normalise to them
     *         (the field is then omitted and the owner attaches the server by hand in the panel)
     */
    static String canonical(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > HYPHENATED_LENGTH && isHyphenatedUuid(value.substring(0, HYPHENATED_LENGTH))) {
            value = value.substring(0, HYPHENATED_LENGTH);
        }
        value = lowerAsciiHex(value.replace("-", ""));
        return isHex(value) ? value : null;
    }

    /**
     * Lower-cases ASCII {@code A-F} only, as the rule says. {@link String#toLowerCase} would also
     * map other characters, some to two {@code char}s, which could change the length the 32-digit
     * check depends on.
     */
    private static String lowerAsciiHex(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            builder.append(c >= 'A' && c <= 'F' ? (char) (c + ('a' - 'A')) : c);
        }
        return builder.toString();
    }

    /** @return {@code true} for 8-4-4-4-12 ASCII hex digits separated by hyphens */
    private static boolean isHyphenatedUuid(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hyphenPosition = i == 8 || i == 13 || i == 18 || i == 23;
            if (hyphenPosition ? c != '-' : !isAsciiHex(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(String value) {
        if (value.length() != HEX_LENGTH) {
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
