package com.ultikits.ultitools.config.document;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.jetbrains.annotations.ApiStatus;

/**
 * The only way the config storage layer replaces a file.
 * <p>
 * The new text is written to a temporary file in the target's own directory, named
 * {@code <file name>.tmp-<16 lower-case hex digits>}, created with the target's POSIX permissions, forced to disk, and
 * moved over the target with {@link StandardCopyOption#ATOMIC_MOVE}. A reader therefore sees either the old file
 * or the complete new one, never an empty or half-written file, and a failure at any step leaves the target
 * byte-identical and removes the temporary file. This replaces Bukkit's {@code FileConfiguration#save(File)},
 * which truncates the target before writing it (UltiKits/UltiTools-Reborn#574).
 * <p>
 * Details:
 * <ul>
 *     <li>Where the file system refuses an atomic move, the temporary file is moved with
 *     {@link StandardCopyOption#REPLACE_EXISTING} only; a warning says so once per JVM.</li>
 *     <li>A target that is a symbolic link stays a link: the file it points to is replaced, beside itself.</li>
 *     <li>Replacing a file creates a new one, so its owner, group, access-control list and extended attributes
 *     are those of a new file the server process creates; only the POSIX permission bits are copied.</li>
 *     <li>A temporary file left behind by a crash is deleted by {@link #deleteStaleTemporaries(Path)} when the
 *     target is next loaded; nothing else in the directory is touched.</li>
 *     <li>After the move, the directory is forced to disk where the platform allows it (best effort).</li>
 * </ul>
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
    private static final Pattern TEMPORARY_SUFFIX = Pattern.compile("[0-9a-f]{16}");
    private static final Set<Path> LIVE_TEMPORARIES = new HashSet<>();
    private static final AtomicBoolean FALLBACK_WARNED = new AtomicBoolean();
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
     * @throws IOException if any step fails; the target is then unchanged and no temporary file remains
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
        boolean staged = false;
        synchronized (LIVE_TEMPORARIES) {
            LIVE_TEMPORARIES.add(temporary.toAbsolutePath().normalize());
        }
        try {
            try (FileChannel channel = files.open(temporary)) {
                ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    files.write(channel, buffer);
                }
                files.force(channel);
            }
            if (Files.exists(destination)) {
                files.copyAttributes(destination, temporary);
            }
            staged = true;
            return new StagedWrite(destination, temporary, files);
        } finally {
            if (!staged) {
                deleteQuietly(temporary);
                release(temporary);
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
                        if (!LIVE_TEMPORARIES.contains(stale.toAbsolutePath().normalize()) && Files.deleteIfExists(stale)) {
                            LOGGER.fine("Removed " + stale + ", a temporary file an interrupted config write left behind");
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not look for stale temporary files beside " + target, e);
        }
    }

    private static void release(Path temporary) {
        synchronized (LIVE_TEMPORARIES) {
            LIVE_TEMPORARIES.remove(temporary.toAbsolutePath().normalize());
        }
    }

    static boolean isTemporaryOf(String fileName, String candidate) {
        return candidate.length() == fileName.length() + TEMPORARY_INFIX.length() + 16
                && candidate.startsWith(fileName + TEMPORARY_INFIX)
                && TEMPORARY_SUFFIX.matcher(candidate.substring(fileName.length() + TEMPORARY_INFIX.length())).matches();
    }

    static String temporaryName(String fileName) {
        StringBuilder name = new StringBuilder(fileName).append(TEMPORARY_INFIX);
        String hex = Long.toHexString(RANDOM.nextLong());
        for (int i = hex.length(); i < 16; i++) {
            name.append('0');
        }
        return name.append(hex).toString();
    }

    /** Test hook: forget that the atomic-move fallback was already reported in this JVM. */
    static void resetFallbackWarning() {
        FALLBACK_WARNED.set(false);
    }

    /** The file actually replaced: the target itself, or the file an existing symbolic link points to. */
    private static Path resolve(Path target) throws IOException {
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
        private final FileOperations files;

        private StagedWrite(Path target, Path temporary, FileOperations files) {
            this.target = target;
            this.temporary = temporary;
            this.files = files;
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
         * The temporary file holding the new content.
         *
         * @return the temporary file
         */
        public Path temporary() {
            return temporary;
        }

        /**
         * Moves the temporary file over the target.
         *
         * @throws IOException if the move fails; the target is then unchanged and the temporary file removed
         */
        public void commit() throws IOException {
            boolean moved = false;
            try {
                try {
                    files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    if (FALLBACK_WARNED.compareAndSet(false, true)) {
                        LOGGER.warning("The file system holding " + target + " cannot replace a file atomically; config"
                                + " files are replaced with an ordinary move (" + e.getMessage() + ")");
                    }
                    files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                moved = true;
            } finally {
                if (!moved) {
                    deleteQuietly(temporary);
                }
                release(temporary);
            }
            syncDirectory(target.getParent());
        }

        /**
         * Removes the temporary file without touching the target.
         *
         * @return whether the temporary file is gone
         */
        public boolean discard() {
            deleteQuietly(temporary);
            release(temporary);
            return !Files.exists(temporary);
        }
    }

    /**
     * The file-system steps of a write and a load, as a seam for fault-injection tests. The default methods are
     * the real operations.
     */
    interface FileOperations {

        default FileChannel open(Path temporary) throws IOException {
            String name = temporary.getFileName().toString();
            Path target = temporary.resolveSibling(name.substring(0, name.length() - TEMPORARY_INFIX.length() - 16));
            PosixFileAttributeView view = Files.getFileAttributeView(target, PosixFileAttributeView.class);
            FileAttribute<?>[] attributes = view != null && Files.exists(target)
                    ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(view.readAttributes().permissions())}
                    : new FileAttribute<?>[0];
            Set<StandardOpenOption> options = new HashSet<>();
            Collections.addAll(options, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return FileChannel.open(temporary, options, attributes);
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

        default byte[] read(Path file) throws IOException {
            return Files.readAllBytes(file);
        }
    }
}
