package com.ultikits.ultitools.utils;

import org.jetbrains.annotations.ApiStatus;

/**
 * Utility class for version checking and comparison operations.
 * Provides methods to check for plugin updates and compare semantic versions.
 *
 * @author wisdomme
 * @since 6.0.0
 * @see VersionComparatorUtil
 */
public class VersionUtils {

    /**
     * Get the newest version of UltiTools from the remote API.
     *
     * <p>Returns {@code null} when the catalogue answers with an error status, a body without a
     * {@code data.version}, or a body that cannot be read, and when it cannot be reached; this
     * contract is unchanged as of 6.3.0. The start-up update check uses
     * {@link #lookUpUltiToolsNewestVersion()} instead, which tells those failures apart from a
     * version.
     *
     * @return the newest UltiTools version string, or {@code null}
     */
    public static String getUltiToolsNewestVersion() {
        return lookUpUltiToolsNewestVersion().getVersion();
    }

    /**
     * Looks up the newest UltiTools version and says whether the lookup established anything
     * (Phase 19, D19-032). {@link VersionLookup.Outcome#FOUND} carries the version exactly as
     * {@link #getUltiToolsNewestVersion()} returns it; every case that method answers with
     * {@code null} is {@link VersionLookup.Outcome#FAILED} here, because the catalogue always names
     * a newest version when it answers normally.
     * <p>
     * An exception thrown while asking (for example a class that can no longer be loaded while the
     * plugin is disabling) propagates, exactly as it does from {@link #getUltiToolsNewestVersion()}.
     *
     * @return the lookup's outcome, never {@code null}
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public static VersionLookup lookUpUltiToolsNewestVersion() {
        return CatalogueClient.frameworkNewest();
    }
}
