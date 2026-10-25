package com.ultikits.ultitools.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Downloads a module JAR and verifies it before anything may use it (plan 22-18, D-28, S-23).
 *
 * <p>A catalogue answer is metadata on the outside of a file: the version names the byte count and
 * the SHA-256 of the JAR it offers, and the download response repeats the hash in {@code
 * X-Content-SHA256}. This class accepts a downloaded file only when the byte count equals the
 * metadata's {@code sizeBytes}, the file's own SHA-256 equals the metadata's {@code sha256}, and
 * the response header, when present, names that same hash. Upper-case hex is accepted from either
 * source; the comparison itself is plain equality, because these are public values and a timing
 * attack on a public hash buys nothing.
 *
 * <p>Metadata without a usable hash or byte count is refused, never treated as "no check": a
 * catalogue that omits the hash cannot downgrade verification. A body longer than {@code
 * sizeBytes} is cut off while streaming instead of being read to the end, and a 3xx is never
 * followed, so the JAR always comes from the host the base URL names.
 *
 * <p>The temporary file never survives a failed verification. It is the caller's file, named
 * inside the modules folder so that accepting it stays one atomic rename.
 *
 * @since 6.3.1
 */
final class VerifiedDownload {

    private static final int SHA256_HEX_LENGTH = 64;

    private VerifiedDownload() {
    }

    /** Why a download was or was not accepted. */
    enum Kind {
        /** Verified: the file at {@link Result#path()} may be used. */
        OK,
        /** The body's byte count differs from the metadata's {@code sizeBytes}. */
        SIZE_MISMATCH,
        /** The body's SHA-256 differs from the metadata's {@code sha256}. */
        HASH_MISMATCH,
        /** The response header names a different hash than the metadata, although the body matches. */
        HEADER_MISMATCH,
        /** The metadata carries no usable {@code sha256} or {@code sizeBytes}. */
        MISSING_METADATA,
        /** The catalogue does not offer this version for download (403, 404). */
        NOT_AVAILABLE,
        /** The exchange did not complete, was redirected, or answered another status. */
        IO_ERROR
    }

    /** What one verified download did. */
    static final class Result {
        private final Kind kind;
        private final Path path;

        private Result(Kind kind, Path path) {
            this.kind = kind;
            this.path = path;
        }

        /** @return why the download was or was not accepted */
        Kind kind() {
            return kind;
        }

        /** @return the verified file, or {@code null} unless the kind is {@link Kind#OK} */
        Path path() {
            return path;
        }
    }

    /**
     * Downloads {@code url} into {@code tempFile} and verifies it.
     *
     * @param url            the absolute download URL, or {@code null} when the version offers none
     * @param expectedSha256 the version's {@code sha256}, as it was written
     * @param expectedSize   the version's {@code sizeBytes}
     * @param tempFile       the file to write and, on success, to keep
     * @return the outcome; never {@code null}
     */
    static Result fetch(String url, String expectedSha256, long expectedSize, Path tempFile) {
        if (!isSha256Hex(expectedSha256) || expectedSize <= 0) {
            return new Result(Kind.MISSING_METADATA, null);
        }
        if (url == null || url.trim().isEmpty()) {
            return new Result(Kind.NOT_AVAILABLE, null);
        }
        CatalogueHttp.Download download = CatalogueHttp.download(url, tempFile,
                expectedSize == Long.MAX_VALUE ? expectedSize : expectedSize + 1);
        Kind verdict = verdictOf(download, expectedSha256, expectedSize);
        if (verdict != Kind.OK) {
            deleteQuietly(tempFile);
            return new Result(verdict, null);
        }
        return new Result(Kind.OK, tempFile);
    }

    private static Kind verdictOf(CatalogueHttp.Download download, String expectedSha256, long expectedSize) {
        switch (download.kind()) {
            case NOT_CONFIGURED:
            case IO_ERROR:
            case REDIRECT:
                return Kind.IO_ERROR;
            case OVERSIZE:
                return Kind.SIZE_MISMATCH;
            default:
                return verdictOfCompleted(download, expectedSha256, expectedSize);
        }
    }

    private static Kind verdictOfCompleted(CatalogueHttp.Download download, String expectedSha256,
                                           long expectedSize) {
        int status = download.status();
        if (status == 403 || status == 404) {
            return Kind.NOT_AVAILABLE;
        }
        if (status < 200 || status > 299) {
            return Kind.IO_ERROR;
        }
        if (download.bytes() != expectedSize) {
            return Kind.SIZE_MISMATCH;
        }
        String header = download.headerSha256();
        if (header != null && !header.trim().equalsIgnoreCase(expectedSha256.trim())) {
            return Kind.HEADER_MISMATCH;
        }
        if (!expectedSha256.trim().equalsIgnoreCase(download.sha256Hex())) {
            return Kind.HASH_MISMATCH;
        }
        return Kind.OK;
    }

    /**
     * @param value a value as written
     * @return whether it is exactly 64 hexadecimal characters
     */
    static boolean isSha256Hex(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.length() != SHA256_HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = Character.toLowerCase(trimmed.charAt(i));
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param hash a hash as written
     * @return at most the first 12 characters, for a local diagnostic line
     */
    static String shortHash(String hash) {
        if (hash == null) {
            return "-";
        }
        String trimmed = hash.trim().toLowerCase(Locale.ROOT);
        return trimmed.length() <= 12 ? trimmed : trimmed.substring(0, 12);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | SecurityException ignored) {
            // Nothing further to do: the caller reports the verification failure either way.
        }
    }
}
