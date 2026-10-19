package com.ultikits.ultitools.utils;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;

import org.jetbrains.annotations.ApiStatus;

/**
 * Turns a module's {@code CodeSource.getLocation()} URL into the {@link File} it names -- the one
 * resolver the language subsystem uses, so language discovery ({@code Localized.supported()}), the
 * dictionary reader and resource extraction in {@code UltiToolsPlugin} can never disagree about
 * where a module's JAR (or exploded class directory) is (#676 case 26).
 * <p>
 * Internal to the framework; not an API for module authors.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class CodeSourceLocations {

    private CodeSourceLocations() {
    }

    /**
     * Resolves a code-source URL as a {@link File}, decoding percent escapes through the URL's
     * {@link java.net.URI}. {@link URL#getPath()} does not decode: a server installed under a path
     * with a space or a non-ASCII character yields {@code %20}/{@code %E6...} escapes that name no
     * file, so {@code new File(url.getPath())} finds nothing there. URI decoding, unlike
     * {@code URLDecoder}, also leaves a literal {@code '+'} in a folder name as a plus sign.
     * <p>
     * Falls back to the raw-path rule the framework used before (the URL's undecoded path, with a
     * leading character dropped when the path is not empty and does not start with {@code '/'}) in
     * two cases, so resolving a location never adds a failure path where reading the raw path never
     * failed:
     * <ul>
     *   <li>{@link URISyntaxException} -- the URL is not a valid URI at all, for example an
     *       unescaped space in a hand-built {@code file:} URL;</li>
     *   <li>{@link IllegalArgumentException} -- {@link File#File(java.net.URI)} rejects a URI with
     *       an authority component, a query or a fragment, or an opaque URI.</li>
     * </ul>
     *
     * @param location the {@code CodeSource.getLocation()} URL, never {@code null}
     * @return the location as a {@link File}; never {@code null}
     */
    public static File toFile(URL location) {
        try {
            return new File(location.toURI());
        } catch (URISyntaxException | IllegalArgumentException notAFileUri) {
            String rawPath = location.getPath();
            return new File(rawPath.isEmpty() || rawPath.startsWith("/") ? rawPath : rawPath.substring(1));
        }
    }
}
