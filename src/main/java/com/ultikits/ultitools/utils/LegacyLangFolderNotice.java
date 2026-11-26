package com.ultikits.ultitools.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.UltiToolsKeys;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.internal.FrameworkMessages;

/** Reports legacy language directories at startup without opening or modifying their contents. */
@ApiStatus.Internal
public final class LegacyLangFolderNotice {
    private static final int DISPLAY_LIMIT = 20;

    private LegacyLangFolderNotice() {
        // Utility class.
    }

    /**
     * Logs one localized INFO notice if legacy language directories are found. The data folder and
     * {@code pluginConfig} may be symbolic links; their discovered module and {@code lang} entries
     * are not followed if linked. A filesystem failure is logged at FINE without discarding
     * directories already found.
     *
     * @param dataFolder the framework data folder
     * @param logger the framework logger
     */
    public static void report(File dataFolder, Logger logger) {
        List<String> folders = new ArrayList<>();
        try {
            Path data = dataFolder.toPath().toAbsolutePath().normalize();
            if (!isDirectory(data)) {
                return;
            }
            // Retain the plugin directory name (normally "plugins") without exposing absolute paths.
            Path displayRoot = data.resolve("..").resolve("..").normalize();
            collectLangDirectory(data, displayRoot, folders);
            collectModuleDirectories(data.resolve("pluginConfig"), displayRoot, folders);
        } catch (DirectoryIteratorException failure) {
            logger.log(Level.FINE, failure.getCause()::toString);
        } catch (IOException | SecurityException failure) {
            logger.log(Level.FINE, failure::toString);
        }
        if (!folders.isEmpty() && logger.isLoggable(Level.INFO)) {
            Collections.sort(folders);
            int shown = Math.min(folders.size(), DISPLAY_LIMIT);
            String summary = String.join(", ", folders.subList(0, shown));
            if (shown < folders.size()) {
                summary += ", " + FrameworkMessages.plain(UltiToolsKeys.STARTUP_LEGACY_LANG_FOLDERS_MORE,
                        Arg.number("count", folders.size() - shown));
            }
            String message = FrameworkMessages.plain(UltiToolsKeys.STARTUP_LEGACY_LANG_FOLDERS,
                    Arg.string("folders", summary));
            logger.info(message.replaceAll("\\R", " "));
        }
    }

    private static void collectModuleDirectories(Path config, Path displayRoot, List<String> folders)
            throws IOException {
        if (!isDirectory(config)) {
            return;
        }
        try (DirectoryStream<Path> modules = Files.newDirectoryStream(config)) {
            for (Path module : modules) {
                if (isDirectory(module, LinkOption.NOFOLLOW_LINKS)) {
                    collectLangDirectory(module, displayRoot, folders);
                }
            }
        }
    }

    private static void collectLangDirectory(Path owner, Path displayRoot, List<String> folders) throws IOException {
        Path lang = owner.resolve("lang");
        if (isDirectory(lang, LinkOption.NOFOLLOW_LINKS)) {
            folders.add(displayRoot.relativize(lang).toString().replace(File.separatorChar, '/'));
        }
    }

    private static boolean isDirectory(Path path, LinkOption... options) throws IOException {
        // Unlike Files.isDirectory, the attribute read distinguishes missing paths from access errors.
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, options).isDirectory();
        } catch (NoSuchFileException absent) {
            return false;
        }
    }
}
