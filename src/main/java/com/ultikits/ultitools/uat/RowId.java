package com.ultikits.ultitools.uat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Reproduces {@code ~/servers/uat/gen-registry.py}'s {@code uid()} scheme byte for byte
 * (Phase 10, D-10-08), so ids computed here agree with every id already recorded in
 * {@code ledger.json} and with {@code uat.py}'s {@code carried_from} bookkeeping.
 * <p>
 * The scheme: join {@code kind}, {@code origin} and every part with the pipe character, take the
 * SHA-1 of the UTF-8 bytes, and prepend the first three characters of {@code kind}, upper-cased,
 * plus a hyphen, to the first eight hex characters of the digest. Argument order matters and must
 * match the Python generator's own call site exactly (kind, origin, class, member, format for a
 * command row).
 *
 * @since 6.3.0
 */
public final class RowId {

    private RowId() {
    }

    /**
     * Computes a row id for {@code kind} occurring at {@code origin}, disambiguated by
     * {@code parts} (in the same order the Python generator passes them).
     *
     * @param kind   the row kind, e.g. {@code "command"} or {@code "help"}
     * @param origin the module (or {@code "framework"}) the row belongs to
     * @param parts  the identity-disambiguating parts, in call-site order
     * @return the row id, e.g. {@code "COM-fc907195"}
     */
    public static String of(String kind, String origin, String... parts) {
        StringBuilder raw = new StringBuilder(kind).append('|').append(origin);
        for (String part : parts) {
            raw.append('|').append(part == null ? "" : part);
        }
        String digestHex = sha1Hex(raw.toString());
        String prefix = kind.length() >= 3 ? kind.substring(0, 3) : kind;
        return prefix.toUpperCase(Locale.ROOT) + "-" + digestHex.substring(0, 8);
    }

    private static String sha1Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is guaranteed present on every conforming JVM (Java Cryptography
            // Architecture Standard Algorithm Name Documentation); this is unreachable.
            throw new IllegalStateException("SHA-1 MessageDigest unavailable", e);
        }
    }
}
