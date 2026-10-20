package com.ultikits.ultitools.utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jetbrains.annotations.ApiStatus;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.interfaces.Localized;

/**
 * What the framework and its modules share about official language files (#608).
 * <p>
 * Official language files -- the {@code lang/*} catalogues the framework and each module ship and write to
 * disk -- are owned by the framework (maintainer decision 2026-10-04). When one differs from the bundled
 * version, it is restored to the bundled version at every start, the previous file is kept as a backup,
 * and one log line tells the operator to customise by copying the official file under a new name and
 * selecting that name in {@code plugins/UltiTools/config.yml}. A custom-named file is the operator's and is
 * never written, replaced or backed up.
 * <p>
 * Internal to the framework; not module-facing API.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class OfficialLanguageFiles {

    /**
     * Framework i18n key for the one line logged when an official language file is restored. Arguments:
     * the restored file, the module ({@code UltiTools} for the framework's own files) and the backup.
     */
    public static final String RESTORED_LOG_KEY = "Language file '%s' of module '%s' differed from the version "
            + "bundled with this release and was restored to it: official language files belong to UltiTools and "
            + "are restored at every start. The previous file was kept as '%s'. To customise messages, copy the "
            + "official file under a new name that starts with its language code and a hyphen, keeping the "
            + "extension (for example zh-myserver), edit the copy, and set language: zh-myserver in "
            + "plugins/UltiTools/config.yml.";

    /**
     * Suffix of a backup: {@code <file>.bak}, then {@code <file>.1.bak}, {@code <file>.2.bak} and so on, so no
     * backup name ends in a catalogue extension the loaders resolve ({@code .json}, {@code .yml},
     * {@code .yaml}) and an existing file is never overwritten.
     */
    public static final String BACKUP_SUFFIX = ".bak";

    /** Upper bound on backup names tried for one file, so a full directory cannot loop forever. */
    public static final int MAX_BACKUP_NAMES = 1000;

    private static final Gson GSON = new Gson();
    private static final Type DICTIONARY_TYPE = new TypeToken<Map<String, String>>() { }.getType();

    private OfficialLanguageFiles() {
    }

    /**
     * Copies {@code file} to the first backup name that does not exist yet, never overwriting anything: a
     * name is skipped when any file or link exists there, and the copy itself refuses an existing target, so
     * a name taken in between is skipped too. The original stays in place.
     *
     * @param file the official language file about to be restored
     * @return the backup created, or {@code null} when all {@link #MAX_BACKUP_NAMES} names are taken
     * @throws IOException if the copy fails for any other reason
     */
    public static File copyToFreshBackup(File file) throws IOException {
        for (int index = 0; index < MAX_BACKUP_NAMES; index++) {
            String name = index == 0 ? file.getName() + BACKUP_SUFFIX
                    : file.getName() + "." + index + BACKUP_SUFFIX;
            File candidate = new File(file.getParentFile(), name);
            if (!Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS) && copyUnlessTaken(file, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean copyUnlessTaken(File file, File candidate) throws IOException {
        try {
            Files.copy(file.toPath(), candidate.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            return true;
        } catch (FileAlreadyExistsException takenMeanwhile) {
            // Created by someone else between the check and the copy: the caller tries the next name.
            return false;
        }
    }

    /**
     * Writes the framework's official language files to {@code <dataFolder>/lang/} so an operator can copy
     * them, and restores any that differ from the bundled version (#608). For each code in {@code codes}
     * whose {@code lang/<code>.json} the framework jar ships:
     * <ul>
     *   <li>absent: written and recorded in the provenance record {@code <dataFolder>/.ultitools-resource-hashes.json};</li>
     *   <li>byte-identical to the bundled version: not written (only a missing record is added);</li>
     *   <li>still the bytes an earlier release wrote (the record matches): replaced by the bundled version,
     *       without a backup -- nobody edited it;</li>
     *   <li>anything else -- edited in place, or written by something other than the framework: kept as a backup
     *       under the first free {@code <name>.bak}/{@code <name>.N.bak} name, then replaced by the bundled version
     *       and recorded, and reported to the caller, which logs {@link #RESTORED_LOG_KEY} once it has a language.</li>
     * </ul>
     * Only the official names {@code <code>.json} are ever written, so an operator's custom-named file in the same
     * folder is never written, replaced or backed up. The replacement is atomic and refuses a read-only or
     * symbolic-link file (the operator pinned it), as for module language files. A failure for one file is logged
     * and leaves that file as it was; the framework reads its official texts from its jar either way.
     *
     * @param dataFolder the framework's data folder, {@code plugins/UltiTools}
     * @param codes      the official language codes the framework ships
     * @param loader     the class loader that reads the framework jar's {@code lang/} resources
     * @param logger     the framework logger
     * @return one {@code {file, backup}} pair per restored file, in {@code codes} order
     */
    public static List<File[]> syncFrameworkFiles(File dataFolder, List<String> codes, ClassLoader loader,
                                                  Logger logger) {
        List<File[]> restored = new ArrayList<>();
        if (dataFolder == null || codes == null || loader == null) {
            return restored;
        }
        for (String code : codes) {
            if (code == null || !Localized.isSafeLanguageCode(code)) {
                continue;
            }
            String resourcePath = "lang/" + code + ".json";
            File file = new File(new File(dataFolder, "lang"), code + ".json");
            try {
                byte[] bundled = readResource(loader, resourcePath);
                File backup = bundled == null ? null : syncFrameworkFile(file, bundled, dataFolder, resourcePath, logger);
                if (backup != null) {
                    restored.add(new File[]{file, backup});
                }
            } catch (IOException | UncheckedIOException e) {
                logger.log(Level.WARNING, "Could not write the official language file '" + file.getPath()
                        + "'; it is left as it is.", e);
            }
        }
        return restored;
    }

    private static File syncFrameworkFile(File file, byte[] bundled, File dataFolder, String resourcePath,
                                          Logger logger) throws IOException {
        String bundledHash = ResourceHashSidecar.sha256(bundled);
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (replace(file, bundled, logger)) {
                ResourceHashSidecar.record(dataFolder, resourcePath, bundledHash);
            }
            return null;
        }
        String diskHash = ResourceHashSidecar.sha256(file);
        Optional<String> recorded = ResourceHashSidecar.readRecordedHash(dataFolder, resourcePath);
        if (diskHash.equals(bundledHash)) {
            if (!recorded.filter(bundledHash::equals).isPresent()) {
                ResourceHashSidecar.record(dataFolder, resourcePath, bundledHash);
            }
            return null;
        }
        if (recorded.filter(diskHash::equals).isPresent()) {
            // Written by an earlier release and never edited since: brought up to date without a backup.
            if (replace(file, bundled, logger)) {
                ResourceHashSidecar.record(dataFolder, resourcePath, bundledHash);
            }
            return null;
        }
        File backup = copyToFreshBackup(file);
        if (backup == null) {
            logger.warning("No free backup name for the official language file '" + file.getPath() + "' after "
                    + MAX_BACKUP_NAMES + " tries; it is left as it is.");
            return null;
        }
        if (!replace(file, bundled, logger)) {
            // The copy is of a file that is still in place, so nothing is lost by removing it.
            Files.deleteIfExists(backup.toPath());
            return null;
        }
        ResourceHashSidecar.record(dataFolder, resourcePath, bundledHash);
        return backup;
    }

    /**
     * Reads the operator's custom framework language file {@code <dataFolder>/lang/<name>.json} (#608). Read only:
     * nothing in the framework ever writes, replaces or backs up a custom-named file. A name that is not a safe
     * language-code token ({@link Localized#isSafeLanguageCode}) is never turned into a path, so it cannot name a
     * file outside {@code lang/}.
     * <p>
     * A key whose custom value lost a placeholder relative to the bundled value of {@code officialCode} uses the
     * bundled value, in memory, with one warning naming the file and the key, never either value (the module
     * guard of #441/#524, {@link UltiToolsPlugin#overrideLostPlaceholders}; gate-1 review R1-2 of plan 17-69).
     *
     * @param dataFolder   the framework's data folder
     * @param name         the configured custom language name
     * @param officialCode the official language the name is based on
     * @param loader       the class loader that reads the framework jar's {@code lang/} resources
     * @param logger       the framework logger
     * @return the custom language, or {@code null} when the name is unsafe, the file does not exist or it cannot
     *         be read or parsed (the reason is logged)
     */
    public static Language readFrameworkCustomFile(File dataFolder, String name, String officialCode,
                                                   ClassLoader loader, Logger logger) {
        if (dataFolder == null || name == null || !Localized.isSafeLanguageCode(name)) {
            return null;
        }
        File file = new File(new File(dataFolder, "lang"), name + ".json");
        if (!file.isFile()) {
            return null;
        }
        try {
            Map<String, String> custom = readJson(Files.readAllBytes(file.toPath()));
            byte[] bundled = loader == null || officialCode == null ? null
                    : readResource(loader, "lang/" + officialCode + ".json");
            Map<String, String> official = bundled == null ? Collections.<String, String>emptyMap() : readJson(bundled);
            return new Language(UltiToolsPlugin.overrideLostPlaceholders(custom, official, (key, reason) ->
                    logger.warning("Language key '" + key + "' in '" + file.getPath() + "' for module 'UltiTools' "
                            + reason + "; using the current bundled version's value for this key.")));
        } catch (IOException | JsonParseException unreadable) {
            logger.log(Level.WARNING, "Could not read the custom language file '" + file.getPath()
                    + "'; framework messages use the official language instead.", unreadable);
            return null;
        }
    }

    private static Map<String, String> readJson(byte[] bytes) {
        Map<String, String> parsed = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), DICTIONARY_TYPE);
        return parsed != null ? parsed : Collections.<String, String>emptyMap();
    }

    private static byte[] readResource(ClassLoader loader, String resourcePath) throws IOException {
        try (InputStream in = loader.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static boolean replace(File file, byte[] bundled, Logger logger) {
        return PosixAttributePreserver.replaceInPlace(file, bundled, new LoggingListener(file, logger));
    }

    /** Logs why a framework language file was not replaced; the file stays as it was in every case but one. */
    private static final class LoggingListener implements PosixAttributePreserver.ReplaceInPlaceListener {
        private final File file;
        private final Logger logger;

        LoggingListener(File file, Logger logger) {
            this.file = file;
            this.logger = logger;
        }

        @Override
        public void onOperatorPinnedReadOnly() {
            logger.warning("The official language file '" + file.getPath() + "' is not writable; it is treated as "
                    + "pinned and left as it is.");
        }

        @Override
        public void onSymbolicLink() {
            logger.warning("The official language file '" + file.getPath() + "' is a symbolic link; it is treated "
                    + "as pinned and left as it is.");
        }

        @Override
        public void onSourceAttributesUnreadable() {
            logger.warning("Could not read the permissions and owner of the official language file '"
                    + file.getPath() + "'; it is left as it is.");
        }

        @Override
        public void onPermissionCopyFailure() {
            logger.warning("Could not keep the permissions of the official language file '" + file.getPath()
                    + "' while writing it.");
        }

        @Override
        public void onOwnershipCopyFailure(String ownerName, String groupName) {
            logger.warning("The official language file '" + file.getPath() + "' is owned by '" + ownerName + ":"
                    + groupName + "', which this process cannot keep; it is left as it is.");
        }

        @Override
        public void onWriteFailure(IOException cause) {
            logger.log(Level.WARNING, "Could not write the official language file '" + file.getPath()
                    + "'; it is left as it is.", cause);
        }
    }
}
