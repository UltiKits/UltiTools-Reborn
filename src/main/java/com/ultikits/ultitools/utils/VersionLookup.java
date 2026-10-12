package com.ultikits.ultitools.utils;

import org.jetbrains.annotations.ApiStatus;

/**
 * The answer to one catalogue version lookup, as the start-up update check needs it: a version was
 * found, the catalogue confirmed it has nothing to offer, or the lookup failed (the catalogue could
 * not be reached, answered with an error status, or sent a body that could not be read).
 * <p>
 * The public lookups ({@link VersionUtils#getUltiToolsNewestVersion()},
 * {@link PluginInstallUtils#getPluginLatestVersion(String)}) fold the last two into {@code null} and
 * keep doing so; this type exists so the update check can report "incomplete" instead of "up to
 * date" when a lookup failed (Phase 19, D19-032).
 * <br>
 * 一次目录版本查询的结果：找到版本、确认没有可提供的版本、或查询失败。
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class VersionLookup {

    /** What the lookup established. */
    public enum Outcome {
        /** The catalogue named a version. */
        FOUND,
        /** The catalogue answered that it has nothing to offer (an unknown module, no compatible or no finalized version). */
        NONE,
        /** Nothing was established: unreachable, an error status, or an unreadable body. */
        FAILED
    }

    /** The failure description for an answer whose status was fine but whose body could not be read. */
    static final String UNREADABLE = "unreadable response";

    /** The prefix {@link SimpleHttpClient} puts on the body it makes up when the request itself failed. */
    private static final String NOT_SENT_PREFIX = "Error: ";

    private static final VersionLookup NONE_OFFERED = new VersionLookup(Outcome.NONE, null, null);

    private final Outcome outcome;
    private final String version;
    private final String failure;

    private VersionLookup(Outcome outcome, String version, String failure) {
        this.outcome = outcome;
        this.version = version;
        this.failure = failure;
    }

    /**
     * @param version the version the catalogue named
     * @return a {@link Outcome#FOUND} lookup
     */
    public static VersionLookup found(String version) {
        return new VersionLookup(Outcome.FOUND, version, null);
    }

    /**
     * @return the {@link Outcome#NONE} lookup
     */
    public static VersionLookup none() {
        return NONE_OFFERED;
    }

    /**
     * @param failure a short description of why the lookup established nothing
     * @return a {@link Outcome#FAILED} lookup
     */
    public static VersionLookup failed(String failure) {
        return new VersionLookup(Outcome.FAILED, null, failure);
    }

    /**
     * Describes a non-2xx answer. {@link SimpleHttpClient} answers an I/O failure (refused, timed
     * out, unknown host) with status 500 and a body starting {@code "Error: "}; that case is
     * described as not reached rather than as a server error.
     *
     * @param status the HTTP status
     * @param body   the body, possibly {@code null}
     * @return a short description for the FINE detail line
     */
    static String describe(int status, String body) {
        if (body != null && body.startsWith(NOT_SENT_PREFIX)) {
            return "not reached (" + body.substring(NOT_SENT_PREFIX.length()) + ")";
        }
        return "HTTP " + status;
    }

    /**
     * @return what the lookup established
     */
    public Outcome getOutcome() {
        return outcome;
    }

    /**
     * @return the version found, or {@code null} unless the outcome is {@link Outcome#FOUND}
     */
    public String getVersion() {
        return version;
    }

    /**
     * @return a short description of why the lookup failed (for example {@code HTTP 500}), or
     *         {@code null} unless the outcome is {@link Outcome#FAILED}
     */
    public String getFailure() {
        return failure;
    }

    /**
     * @return {@code true} when the lookup established nothing
     */
    public boolean isFailed() {
        return outcome == Outcome.FAILED;
    }

    @Override
    public String toString() {
        return outcome == Outcome.FAILED ? "FAILED(" + failure + ")" : outcome + "(" + version + ")";
    }
}
