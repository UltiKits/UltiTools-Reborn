package com.ultikits.ultitools.utils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The single atomic, owner-only file writer behind both framework-owned files under {@code <server root>/.ultikits/}:
 * {@link CredentialStore}'s {@code credentials.json} and {@link CloudStateStore}'s {@code cloud-outbox.json}. It was
 * extracted from {@code CredentialStore.writeLocked} (plan 22-03, S-15) so the durability rule lives in one place;
 * both callers delegate here and neither keeps a copy.
 * <p>
 * One write, in order: create the parent directories; create the temporary file in the target's own directory
 * (owner-only, {@code rw-------}, at creation on a POSIX file system, and narrowed again after opening in case an
 * interrupted earlier write left the same name with another mode, D19-060); write the body through a UTF-8 encoder;
 * flush and force the bytes to disk; optionally run a hook; replace the target with
 * {@link StandardCopyOption#ATOMIC_MOVE} and {@link StandardCopyOption#REPLACE_EXISTING}; force the directory
 * (best-effort). A crash therefore leaves either the previous complete file or the new complete file, never a torn
 * one, and a power loss cannot leave the target naming bytes that never reached the disk (#573). When the move did
 * not happen, the temporary file is removed.
 * <p>
 * This class decides nothing about what is written or whether a write is allowed: it never reads the target, so it
 * cannot judge operator content. Each caller states in its own javadoc why its write path cannot overwrite
 * operator-written configuration (the configuration-layer rule, D-31).
 */
final class OwnerOnlyAtomicFile {

    /** Owner read and write only: the mode of every file this class writes on POSIX (D19-060). */
    static final Set<PosixFilePermission> OWNER_ONLY =
            Collections.unmodifiableSet(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

    private static final Logger LOGGER = Logger.getLogger(OwnerOnlyAtomicFile.class.getName());

    /** Writes the complete content of the file to {@code out}; the writer is flushed and closed by the caller. */
    @FunctionalInterface
    interface WriterBody {
        /**
         * @param out the UTF-8 writer on the temporary file
         * @throws IOException if writing fails; the target is then left unchanged
         */
        void writeTo(Writer out) throws IOException;
    }

    private OwnerOnlyAtomicFile() {
    }

    /**
     * Replaces {@code target} atomically with the content {@code body} writes.
     *
     * @param target       the file to replace (or create)
     * @param tempFileName the bare name of the temporary file, created in {@code target}'s directory
     * @param body         writes the complete new content
     * @throws AtomicMoveNotSupportedException if the file system cannot replace the target atomically; thrown
     *                                         unchanged so a caller can report it with its own message, never
     *                                         silently replaced by a non-atomic copy
     * @throws IOException                     if any other step fails; the target is then left unchanged
     */
    static void write(Path target, String tempFileName, WriterBody body) throws IOException {
        write(target, tempFileName, body, null);
    }

    /**
     * As {@link #write(Path, String, WriterBody)}, running {@code beforeMove} after the temporary file is complete
     * and forced to disk and before the atomic move. Only {@link CloudStateStore}'s test seam passes a hook.
     *
     * @param beforeMove run between the forced temporary file and the move, or {@code null}
     */
    static void write(Path target, String tempFileName, WriterBody body, Runnable beforeMove) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tempFile = (parent != null ? parent : target).resolve(tempFileName);
        boolean moved = false;
        try {
            // The temporary file becomes the target at the atomic move below, so its mode is the file's mode. On a
            // POSIX file system it is created owner-only (rw-------), and narrowed again in case an earlier,
            // interrupted write left it with another mode; otherwise every write would widen a file the operator
            // restricted to 600 to the process umask (D19-060). Other file systems are unchanged.
            boolean posix = tempFile.getFileSystem().supportedFileAttributeViews().contains("posix");
            FileAttribute<?>[] ownerOnly = posix
                    ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY)}
                    : new FileAttribute<?>[0];
            try (FileChannel channel = FileChannel.open(tempFile, EnumSet.of(StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE), ownerOnly);
                 Writer writer = new BufferedWriter(new OutputStreamWriter(
                         Channels.newOutputStream(channel), StandardCharsets.UTF_8.newEncoder()))) {
                if (posix) {
                    Files.setPosixFilePermissions(tempFile, OWNER_ONLY);
                }
                body.writeTo(writer);
                writer.flush();
                // The bytes must reach the disk before the rename can, or a power loss could leave the target
                // naming an empty file (#573).
                channel.force(true);
            }
            if (beforeMove != null) {
                beforeMove.run();
            }
            Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
            forceDirectory(parent);
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Best-effort cleanup only; the write already failed and is reported by the caller.
                }
            }
        }
    }

    /**
     * Forces a directory's entries to disk after the rename, so the rename itself survives a power loss.
     * Best-effort: Windows cannot open a directory as a channel, and a failure here cannot undo a rename that
     * already happened, so it is logged at {@link Level#FINE} and not reported as a failed write.
     */
    private static void forceDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            LOGGER.log(Level.FINE, "Could not force the directory " + directory + " to disk: " + e);
        }
    }
}
