package com.ultikits.ultitools.config.document;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.security.SecureRandom;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.jetbrains.annotations.ApiStatus;

/**
 * The only way the config storage layer replaces a file.
 * <p>
 * Normally the new UTF-8 text is written to a same-directory temporary file with the target's POSIX
 * permissions, forced to disk, and atomically moved over the target. Eligible atomic-replace failures
 * (unsupported atomic moves, EBUSY or EXDEV) and temporary-creation permission/read-only refusals use a
 * narrow fallback: exclusively create a backup named {@code <file name>.ultitools-backup-<16 lower-case hex>},
 * copy and force the old content, then overwrite and force the existing target in place. A backup this server
 * run already recorded for the same target, whose bytes still match the record, is refreshed through a forced
 * same-directory temporary and atomic replacement before the target is opened. A complete forced backup
 * remains until the next successful strict UTF-8 load, which deletes it only while its bytes still match what
 * the writer recorded; any other file of that pattern is kept and named once at INFO. Each fallback attempt
 * logs its path, cause and outcome.
 * <p>
 * <b>Why it cannot overwrite operator content.</b> The writer reads, writes or deletes only the target it was
 * given and names of its own two patterns, {@code <file name>.tmp-<16 lower-case hex>} and the backup pattern
 * above; an operator's {@code <file>.bak}, or any other name, is never touched (UltiKits/UltiTools-Reborn#601).
 * <p>
 * Before the in-place target is opened, failures leave its bytes unchanged. Once that open is attempted,
 * an I/O failure may leave a partial target, but its complete forced backup is retained. No automatic
 * restoration is attempted. A symbolic link stays a link: writes and backup cleanup use its resolved target.
 * Stale temporary files are cleaned on load; live staged writes are protected. The atomic path copies only
 * POSIX permission bits, not ownership, ACLs or extended attributes. Directory syncing is best effort.
 * This is a framework-internal writer for config files; crash-safe writes of a module's own files are a
 * separate, later feature (UltiKits/UltiTools-Reborn#545).
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class AtomicConfigWriter {

    private static final Logger LOGGER = Logger.getLogger(AtomicConfigWriter.class.getName());
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TEMPORARY_INFIX = ".tmp-";
    private static final String BACKUP_INFIX = ".ultitools-backup-";
    private static final Pattern TEMPORARY_SUFFIX = Pattern.compile("[0-9a-f]{16}");
    private static final Set<Path> LIVE_TEMPORARIES = new HashSet<>();
    /** Serializes replacement and successful-load backup cleanup within this JVM. */
    static final Object WRITE_LOAD_LOCK = new Object();
    /** The backup this server run created for each target (resolved path), with its SHA-256; guarded by WRITE_LOAD_LOCK. */
    private static final Map<Path, RecordedBackup> BACKUPS = new HashMap<>();
    /** Backups of the framework's pattern already named at INFO this server run; guarded by WRITE_LOAD_LOCK. */
    private static final Set<Path> NAMED_LEFTOVERS = new HashSet<>();
    private static final FileOperations FILES = new FileOperations() {
    };
    /** The real file operations, for {@link ConfigDocument#load(Path)}. */
    static final FileOperations FILES_FOR_LOAD = FILES;

    private AtomicConfigWriter() {
    }

    /**
     * Replaces {@code target} with {@code text}, encoded as UTF-8.
     *
     * @param target the file to replace; its parent directory must exist
     * @param text   the complete new content
     * @throws IOException if a step fails; before in-place open the target is unchanged, otherwise a complete backup remains
     */
    public static void write(Path target, String text) throws IOException {
        write(target, text, FILES);
    }

    static void write(Path target, String text, FileOperations files) throws IOException {
        stage(target, text, files).commit();
    }

    /**
     * Writes {@code text} to a temporary file beside {@code target} without touching the target, so several
     * files can be prepared before any of them is replaced. The caller must {@link StagedWrite#commit() commit}
     * or {@link StagedWrite#discard() discard} it, and may load the target in between; in-process staged files are protected from stale cleanup.
     *
     * If temporary creation is refused for permissions or a read-only file system, staging keeps the UTF-8
     * bytes in memory without touching the target or backup. Commit attempts the backed in-place path.
     *
     * @param target the file to replace later; its parent directory must exist
     * @param text   the complete new content
     * @return the staged write
     * @throws IOException if the temporary file cannot be written; nothing is then left behind
     */
    public static StagedWrite stage(Path target, String text) throws IOException {
        return stage(target, text, FILES);
    }

    static StagedWrite stage(Path target, String text, FileOperations files) throws IOException {
        Path destination = resolve(target);
        Path temporary = destination.resolveSibling(temporaryName(destination.getFileName().toString()));
        Path identity = temporaryIdentity(temporary);
        boolean staged = false;
        synchronized (LIVE_TEMPORARIES) {
            LIVE_TEMPORARIES.add(identity);
        }
        try {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            FileAttribute<?>[] attributes = files.temporaryAttributes(destination);
            FileChannel opened;
            try {
                opened = files.open(temporary, attributes);
            } catch (IOException failure) {
                if (!temporaryCreationRefused(failure)) {
                    throw failure;
                }
                // An open implementation may have created a partial file before reporting refusal.
                files.delete(temporary);
                staged = true;
                return new StagedWrite(destination, temporary, identity, files, data, failure);
            }
            try (FileChannel channel = opened) {
                writeAll(files, channel, data);
                files.force(channel);
            }
            if (Files.exists(destination)) {
                files.copyAttributes(destination, temporary);
            }
            staged = true;
            return new StagedWrite(destination, temporary, identity, files, null, null);
        } finally {
            if (!staged) {
                deleteQuietly(temporary);
                release(identity);
            }
        }
    }

    /**
     * Deletes the temporary files an interrupted write left beside {@code target}: only names of the exact form
     * {@code <target file name>.tmp-<16 lower-case hex digits>}. Each deletion is logged at FINE. Failures are
     * ignored; the next load tries again.
     *
     * @param target the config file about to be loaded
     */
    static void deleteStaleTemporaries(Path target) {
        try {
            Path destination = resolve(target);
            Path directory = destination.getParent();
            String name = destination.getFileName().toString();
            if (directory == null || !Files.isDirectory(directory)) {
                return;
            }
            try (DirectoryStream<Path> candidates = Files.newDirectoryStream(directory,
                    entry -> isTemporaryOf(name, entry.getFileName().toString()))) {
                for (Path stale : candidates) {
                    synchronized (LIVE_TEMPORARIES) {
                        if (!LIVE_TEMPORARIES.contains(temporaryIdentity(stale)) && Files.deleteIfExists(stale)) {
                            LOGGER.fine("Removed " + stale + ", a temporary file an interrupted config write left behind");
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not look for stale temporary files beside " + target, e);
        }
    }

    private static void release(Path identity) {
        synchronized (LIVE_TEMPORARIES) {
            LIVE_TEMPORARIES.remove(identity);
        }
    }

    /** Identifies a reserved file through its real parent, even before the file exists. */
    private static Path temporaryIdentity(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        Path ancestor = parent;
        while (ancestor != null) {
            try {
                Path real = ancestor.toRealPath();
                return real.resolve(ancestor.relativize(parent)).resolve(absolute.getFileName());
            } catch (NoSuchFileException missing) {
                ancestor = ancestor.getParent();
            }
        }
        throw new NoSuchFileException(absolute.toString());
    }

    static boolean isTemporaryOf(String fileName, String candidate) {
        return candidate.length() == fileName.length() + TEMPORARY_INFIX.length() + 16
                && candidate.startsWith(fileName + TEMPORARY_INFIX)
                && TEMPORARY_SUFFIX.matcher(candidate.substring(fileName.length() + TEMPORARY_INFIX.length())).matches();
    }

    static String temporaryName(String fileName) {
        return randomName(fileName, TEMPORARY_INFIX);
    }

    /** Whether {@code candidate} has the exact form {@code <fileName>.ultitools-backup-<16 lower-case hex>}. */
    static boolean isBackupOf(String fileName, String candidate) {
        return candidate.length() == fileName.length() + BACKUP_INFIX.length() + 16
                && candidate.startsWith(fileName + BACKUP_INFIX)
                && TEMPORARY_SUFFIX.matcher(candidate.substring(fileName.length() + BACKUP_INFIX.length())).matches();
    }

    private static String randomName(String fileName, String infix) {
        StringBuilder name = new StringBuilder(fileName).append(infix);
        String hex = Long.toHexString(RANDOM.nextLong());
        for (int i = hex.length(); i < 16; i++) {
            name.append('0');
        }
        return name.append(hex).toString();
    }

    private static boolean temporaryCreationRefused(IOException failure) {
        return failure instanceof AccessDeniedException || failure instanceof FileSystemException
                && "Read-only file system".equals(((FileSystemException) failure).getReason());
    }

    private static boolean atomicReplacementRefused(IOException failure) {
        if (failure instanceof AtomicMoveNotSupportedException) {
            return true;
        }
        if (!(failure instanceof FileSystemException)) {
            return false;
        }
        String reason = ((FileSystemException) failure).getReason();
        return "EBUSY".equals(reason) || "EXDEV".equals(reason) || "Device or resource busy".equals(reason)
                || "Invalid cross-device link".equals(reason);
    }

    private static void writeAll(FileOperations files, FileChannel channel, byte[] data) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) {
            files.write(channel, buffer);
        }
    }

    /**
     * After a successful strict load: deletes the backup this server run recorded for {@code destination} while
     * its bytes still match the record, and names once at INFO every other file of the backup pattern beside it
     * (left by an earlier run, or changed since it was written), which is kept. Nothing else is read or deleted.
     * Failures are logged at FINE; the next load tries again. Called under {@link #WRITE_LOAD_LOCK}.
     *
     * @param destination the loaded file (a symbolic link already resolved)
     * @param files       the file operations
     */
    static void deleteBackupAfterLoad(Path destination, FileOperations files) {
        try {
            Path directory = destination.getParent();
            String name = destination.getFileName().toString();
            if (directory == null || !Files.isDirectory(directory)) {
                return;
            }
            List<Path> candidates = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory,
                    entry -> isBackupOf(name, entry.getFileName().toString()))) {
                for (Path candidate : entries) {
                    candidates.add(candidate);
                }
            }
            RecordedBackup recorded = BACKUPS.get(destination);
            for (Path candidate : candidates) {
                if (recorded != null && candidate.equals(recorded.path) && recorded.matches(files)) {
                    deleteRecorded(destination, candidate, files);
                } else if (NAMED_LEFTOVERS.add(candidate)) {
                    LOGGER.info("Kept " + candidate + ": a config backup this server run did not create, or whose bytes"
                            + " changed after it was written; delete it once it is no longer needed");
                }
            }
            if (recorded != null && !candidates.contains(recorded.path)) {
                BACKUPS.remove(destination);
            }
        } catch (IOException | RuntimeException failure) {
            LOGGER.log(Level.FINE, "Could not look for config backups beside " + destination, failure);
        }
    }

    private static void deleteRecorded(Path destination, Path backup, FileOperations files) {
        try {
            files.delete(backup);
            BACKUPS.remove(destination);
        } catch (IOException | RuntimeException failure) {
            LOGGER.log(Level.FINE, "Could not delete loaded config backup " + backup, failure);
        }
    }

    /** The file actually replaced: the target itself, or the file an existing symbolic link points to. */
    static Path resolve(Path target) throws IOException {
        Path absolute = target.toAbsolutePath();
        return Files.isSymbolicLink(absolute) ? absolute.toRealPath() : absolute;
    }

    private static void deleteQuietly(Path temporary) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not delete " + temporary + "; the next load of its target removes it", e);
        }
    }

    private static void syncDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException e) {
            // Not every platform can open or force a directory (Windows cannot); the file itself is on disk.
            LOGGER.log(Level.FINEST, "Directory " + directory + " not forced to disk", e);
        }
    }

    /** A temporary file written beside its target and not yet moved over it. */
    public static final class StagedWrite {

        private final Path target;
        private final Path temporary;
        private final Path identity;
        private final FileOperations files;
        private final byte[] data;
        private final IOException deferredCause;

        private StagedWrite(Path target, Path temporary, Path identity, FileOperations files, byte[] data, IOException deferredCause) {
            this.target = target;
            this.temporary = temporary;
            this.identity = identity;
            this.files = files;
            this.data = data;
            this.deferredCause = deferredCause;
        }

        /**
         * The file this write replaces (a symbolic link already resolved).
         *
         * @return the target
         */
        public Path target() {
            return target;
        }

        /**
         * The temporary file holding the new content on the normal path. After an eligible temporary-create
         * refusal, this is a reserved same-directory path that may not exist; the bytes remain in memory.
         *
         * @return the temporary file
         */
        public Path temporary() {
            return temporary;
        }

        /**
         * Atomically replaces the target, or uses the backed in-place path on an eligible refusal.
         *
         * @throws IOException if replacement fails; a failed in-place attempt retains a complete forced backup
         */
        public void commit() throws IOException {
            synchronized (WRITE_LOAD_LOCK) {
                try {
                    if (data != null) {
                        replaceInPlace(data, deferredCause);
                    } else {
                        try {
                            files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException failure) {
                            if (!atomicReplacementRefused(failure)) {
                                throw failure;
                            }
                            replaceInPlace(null, failure);
                        }
                    }
                } finally {
                    deleteQuietly(temporary);
                    release(identity);
                }
                syncDirectory(target.getParent());
            }
        }

        private void replaceInPlace(byte[] replacement, IOException cause) throws IOException {
            RecordedBackup recorded = BACKUPS.get(target);
            boolean refresh = recorded != null && recorded.matches(files);
            Path backup = refresh ? recorded.path
                    : target.resolveSibling(randomName(target.getFileName().toString(), BACKUP_INFIX));
            boolean created = false;
            boolean forced = false;
            boolean success = false;
            try {
                byte[] content = replacement == null ? files.read(temporary) : replacement;
                byte[] original = files.read(target);
                FileAttribute<?>[] attributes = files.temporaryAttributes(target);
                if (refresh) {
                    refreshBackup(backup, original, attributes);
                    forced = true;
                } else {
                    // A fresh framework-only name, created exclusively: never an existing file.
                    try (FileChannel channel = files.openBackup(backup, attributes)) {
                        created = true;
                        writeAll(files, channel, original);
                        files.force(channel);
                        forced = true;
                    }
                }
                BACKUPS.put(target, new RecordedBackup(backup, ConfigDocument.sha256(original)));
                syncDirectory(target.getParent());
                try (FileChannel channel = files.openTarget(target)) {
                    writeAll(files, channel, content);
                    files.force(channel);
                }
                success = true;
            } finally {
                if (created && !forced) {
                    deleteQuietly(backup);
                }
                LOGGER.warning("Config " + target + " in-place replacement with backup " + backup
                        + (success ? " succeeded" : " failed") + "; cause: " + cause.getMessage());
            }
        }

        /** Refreshes a retained backup without exposing a partial replacement. */
        private void refreshBackup(Path backup, byte[] original, FileAttribute<?>[] attributes) throws IOException {
            Path copy = backup.resolveSibling(temporaryName(backup.getFileName().toString()));
            try {
                try (FileChannel channel = files.open(copy, attributes)) {
                    writeAll(files, channel, original);
                    files.force(channel);
                }
                files.move(copy, backup, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                deleteQuietly(copy);
            }
        }

        /**
         * Publishes the staged text as a new file, for a target that did not exist when it was read: never
         * replaces a file that exists by then. The temporary file is hard-linked onto the target, which fails if
         * the target exists; where the file system refuses links, the target is checked again immediately before
         * a move that does not replace. The temporary name is removed either way.
         *
         * @throws FileAlreadyExistsException if a file appeared at the target; it is left as it is
         * @throws IOException                if publishing fails otherwise; no file was replaced
         */
        void commitAsNewFile() throws IOException {
            synchronized (WRITE_LOAD_LOCK) {
                try {
                    if (data != null) {
                        throw deferredCause;
                    }
                    try {
                        files.link(target, temporary);
                    } catch (FileAlreadyExistsException appeared) {
                        throw appeared;
                    } catch (UnsupportedOperationException | FileSystemException noLinks) {
                        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                            throw new FileAlreadyExistsException(target.toString());
                        }
                        files.move(temporary, target);
                    }
                } finally {
                    deleteQuietly(temporary);
                    release(identity);
                }
                syncDirectory(target.getParent());
            }
        }

        /**
         * Removes the temporary file without touching the target.
         *
         * @return whether the temporary file is gone
         */
        public boolean discard() {
            deleteQuietly(temporary);
            release(identity);
            return !Files.exists(temporary);
        }
    }

    /** A backup this server run wrote, and the SHA-256 of the bytes it wrote there. */
    private static final class RecordedBackup {

        private final Path path;
        private final String sha256;

        RecordedBackup(Path path, String sha256) {
            this.path = path;
            this.sha256 = sha256;
        }

        /** Whether the backup is still a regular file holding exactly the recorded bytes. */
        boolean matches(FileOperations files) {
            try {
                return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && sha256.equals(ConfigDocument.sha256(files.read(path)));
            } catch (IOException | RuntimeException unreadable) {
                return false;
            }
        }
    }

    /**
     * The file-system steps of a write and a load, as a seam for fault-injection tests. The default methods are
     * the real operations.
     */
    interface FileOperations {

        default FileAttribute<?>[] temporaryAttributes(Path target) throws IOException {
            PosixFileAttributeView view = Files.getFileAttributeView(target, PosixFileAttributeView.class);
            return view != null && Files.exists(target)
                    ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(view.readAttributes().permissions())}
                    : new FileAttribute<?>[0];
        }

        default FileChannel open(Path temporary, FileAttribute<?>... attributes) throws IOException {
            Set<StandardOpenOption> options = new HashSet<>();
            Collections.addAll(options, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return FileChannel.open(temporary, options, attributes);
        }

        default FileChannel openBackup(Path backup, FileAttribute<?>... attributes) throws IOException {
            Set<StandardOpenOption> options = new HashSet<>();
            Collections.addAll(options, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return FileChannel.open(backup, options, attributes);
        }

        default FileChannel openTarget(Path target) throws IOException {
            return FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        default void delete(Path file) throws IOException {
            Files.deleteIfExists(file);
        }

        default void write(FileChannel channel, ByteBuffer data) throws IOException {
            channel.write(data);
        }

        default void force(FileChannel channel) throws IOException {
            channel.force(true);
        }

        default void copyAttributes(Path from, Path to) throws IOException {
            PosixFileAttributeView source = Files.getFileAttributeView(from, PosixFileAttributeView.class);
            PosixFileAttributeView destination = Files.getFileAttributeView(to, PosixFileAttributeView.class);
            if (source != null && destination != null) {
                destination.setPermissions(source.readAttributes().permissions());
            }
        }

        default void move(Path source, Path destination, CopyOption... options) throws IOException {
            Files.move(source, destination, options);
        }

        default void link(Path link, Path existing) throws IOException {
            Files.createLink(link, existing);
        }

        default byte[] read(Path file) throws IOException {
            return Files.readAllBytes(file);
        }
    }
}
