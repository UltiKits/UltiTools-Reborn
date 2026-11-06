package com.ultikits.ultitools.config.document;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Complete publication only, selected solely by the opt-in YAML gate. Never opens the final target for writing.
 * All uses are under WRITE_LOAD_LOCK. No scanning/recovery or inference of prior temporary ownership is performed.
 */
final class StrictYamlPublication {
    /** Filesystem seam, captured once per invocation; null selects real operations. */
    static final AtomicReference<Operations> FILES = new AtomicReference<>();
    private static final Operations REAL = new Operations() { };
    private static final Logger LOGGER = Logger.getLogger(StrictYamlPublication.class.getName());
    private static final Set<String> WARNED_SYNC = ConcurrentHashMap.newKeySet();
    private static final Set<PosixFilePermission> PRIVATE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> NONE = Collections.emptySet();

    private final Target destination;
    private final Path temporary;
    private Object fileKey;

    private StrictYamlPublication(Target destination, Path temporary) {
        this.destination = destination;
        this.temporary = temporary;
    }

    /** Policy refusal, distinguished from an I/O failure and reported once by the gate without values. */
    static final class Refused extends IOException {
        private static final long serialVersionUID = 1L;
        Refused(String reason) {
            super(reason);
        }
    }

    /** A changed/deleted file or an existing creation target is not permission to write it. */
    static final class Changed extends IOException {
        private static final long serialVersionUID = 1L;
        Changed(String reason) {
            super(reason);
        }
    }

    /** Anchors only the real parent, never lexically normalizing away a symlink followed by '..'. */
    static Target prepare(Path file, boolean creating) throws IOException {
        Operations operations = FILES.get();
        Operations files = operations == null ? REAL : operations;
        if (!file.getFileSystem().equals(FileSystems.getDefault())) {
            throw new Refused("strict YAML publication requires the default local POSIX filesystem");
        }
        Path absolute = file.toAbsolutePath();
        checkKind(absolute, creating, files);
        Path parent = absolute.getParent();
        if (parent == null || absolute.getFileName() == null) {
            throw new Refused("the destination must have an existing parent and a file name");
        }
        Path realParent = parent.toRealPath();
        Path target = realParent.resolve(absolute.getFileName());
        checkKind(target, creating, files);
        return new Target(target, creating, files);
    }

    private static void checkKind(Path file, boolean creating, Operations files) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = files.basic(file);
        } catch (NoSuchFileException absent) {
            if (!creating) {
                throw new Changed("the snapshot's file no longer exists");
            }
            return;
        }
        if (creating) {
            throw new Changed("an entry already exists at the creation target");
        }
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new Refused("strict YAML edits require a regular file, never a final symbolic link or special file");
        }
    }

    /** The path/kind preflight; publication restrictions are checked only once a real edit needs staging. */
    static final class Target {
        final Path path;
        private final boolean creating;
        private final Operations files;
        private Metadata expected;

        private Target(Path path, boolean creating, Operations files) {
            this.path = path;
            this.creating = creating;
            this.files = files;
        }

        StrictYamlPublication stage(String text) throws IOException {
            checkKind(path, creating, files);
            requirePosix(path.getParent(), files);
            if (!creating) {
                expected = new Metadata(requirePosix(path, files));
                if (!expected.hasWriteBit() || !files.writable(path)) {
                    throw new Refused("the target is deliberately read-only or not writable by this process");
                }
            }
            // Retry only an exclusive name collision. No existing entry is ever reused or cleaned.
            for (;;) {
                Path temp = path.resolveSibling(path.getFileName() + ".ultitools-stage-" + files.nonce() + ".tmp");
                FileChannel channel;
                try {
                    channel = files.open(temp);
                } catch (FileAlreadyExistsException collision) {
                    continue;
                } catch (UnsupportedOperationException failure) {
                    throw new IOException("Private POSIX staging is unsupported", failure);
                }
                return finishStage(temp, channel, text);
            }
        }

        /** Completes and closes exactly the channel just created; cleanup keeps the same primary failure. */
        private StrictYamlPublication finishStage(Path temp, FileChannel channel, String text) throws IOException {
            StrictYamlPublication staged = new StrictYamlPublication(this, temp);
            try {
                try (Opened opened = new Opened(channel, files)) {
                    staged.fileKey = files.basic(temp).fileKey();
                    PosixFileAttributes initial = requirePosix(temp, files);
                    Metadata desired = creating ? new Metadata(PRIVATE, initial.owner(), initial.group()) : expected;
                    if (!initial.permissions().equals(PRIVATE)) {
                        throw new IOException("Private staging metadata could not be verified");
                    }
                    ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                    while (bytes.hasRemaining()) {
                        files.write(opened.channel, bytes);
                    }
                    desired.apply(temp, initial, files);
                    files.force(opened.channel);
                    if (!desired.matches(requirePosix(temp, files))) {
                        throw new IOException("Promised staging metadata could not be verified");
                    }
                }
                return staged;
            } catch (IOException | RuntimeException | Error failure) {
                staged.discard(failure);
                throw failure;
            }
        }
    }

    /** A close failure remains pre-commit; try-with-resources preserves an earlier failure as primary. */
    private static final class Opened implements AutoCloseable {
        private final FileChannel channel;
        private final Operations files;
        Opened(FileChannel channel, Operations files) {
            this.channel = channel;
            this.files = files;
        }
        @Override
        public void close() throws IOException {
            try {
                files.close(channel);
            } finally {
                // The seam may throw before closing; the actual created channel must still be released.
                if (channel.isOpen()) {
                    channel.close();
                }
            }
        }
    }

    private static PosixFileAttributes requirePosix(Path path, Operations files) throws IOException {
        if (files.hasAcl(path)) {
            throw new Refused("strict YAML publication does not support an exposed ACL view");
        }
        PosixFileAttributes attributes = files.posix(path);
        if (attributes == null) {
            throw new Refused("strict YAML publication requires working POSIX attributes");
        }
        return attributes;
    }

    private static final class Metadata {
        private final Set<PosixFilePermission> permissions;
        private final UserPrincipal owner;
        private final GroupPrincipal group;
        Metadata(PosixFileAttributes attributes) {
            this(attributes.permissions(), attributes.owner(), attributes.group());
        }
        Metadata(Set<PosixFilePermission> permissions, UserPrincipal owner, GroupPrincipal group) {
            this.permissions = new HashSet<>(permissions);
            this.owner = owner;
            this.group = group;
        }
        boolean hasWriteBit() {
            return permissions.contains(PosixFilePermission.OWNER_WRITE) || permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE);
        }
        void apply(Path temp, PosixFileAttributes initial, Operations files) throws IOException {
            boolean changeOwner = !owner.equals(initial.owner());
            boolean changeGroup = !group.equals(initial.group());
            if (changeOwner || changeGroup) {
                files.permissions(temp, NONE);
                if (changeOwner) {
                    files.owner(temp, owner);
                }
                if (changeGroup) {
                    files.group(temp, group);
                }
            }
            files.permissions(temp, permissions);
        }
        boolean matches(PosixFileAttributes now) {
            return now != null && permissions.equals(now.permissions()) && owner.equals(now.owner()) && group.equals(now.group());
        }
    }

    /** The gate checks this before reading current bytes, immediately before publication. */
    boolean matchesTarget() throws IOException {
        try {
            BasicFileAttributes now = destination.files.basic(destination.path);
            if (destination.creating) {
                return false;
            }
            return now.isRegularFile() && !now.isSymbolicLink()
                    && !destination.files.hasAcl(destination.path)
                    && destination.expected.matches(destination.files.posix(destination.path));
        } catch (NoSuchFileException absent) {
            return destination.creating;
        }
    }

    /** Successful atomic move is the replacement commit point; never retry with weaker semantics. */
    void commit() throws IOException {
        try {
            destination.files.move(temporary, destination.path);
        } catch (UnsupportedOperationException failure) {
            throw new IOException("Atomic YAML replacement is unsupported", failure);
        }
        syncDirectory();
    }

    /** Native POSIX link's new-entry operation, not ATOMIC_MOVE used as create-if-absent. */
    void commitAsNewFile() throws IOException {
        try {
            destination.files.link(destination.path, temporary);
        } catch (UnsupportedOperationException failure) {
            throw new IOException("Exclusive YAML link publication is unsupported", failure);
        }
        syncDirectory();
        discard(null);
        syncDirectory();
    }

    /** Best effort only after commit: never imply that committed content was not published. */
    private void syncDirectory() {
        Path directory = destination.path.getParent();
        try {
            destination.files.syncDirectory(directory);
        } catch (IOException | RuntimeException failure) {
            String reason = failure.getClass().getName();
            if (WARNED_SYNC.add(directory + "\n" + reason)) {
                LOGGER.warning("YAML was published, but directory " + directory + " could not be forced (" + reason
                        + "); complete publication is not a power-loss durability guarantee");
            }
        }
    }

    /** Cleanup requires current regular-file kind and the same non-null key recorded at exclusive creation. */
    boolean discard(Throwable primary) {
        try {
            BasicFileAttributes now = destination.files.basic(temporary);
            if (fileKey == null || !now.isRegularFile() || now.isSymbolicLink() || !fileKey.equals(now.fileKey())) {
                retained("its current file identity cannot be proved");
                return false;
            }
            destination.files.delete(temporary);
            return true;
        } catch (NoSuchFileException absent) {
            return true;
        } catch (IOException | RuntimeException failure) {
            suppressCleanupFailure(primary, failure);
            retained("cleanup failed (" + failure.getClass().getSimpleName() + ")");
            return false;
        }
    }

    /** Throwable.addSuppressed rejects self-suppression by identity, not by an overridable equals implementation. */
    @SuppressWarnings("PMD.CompareObjectsWithEquals") // Identity is required by Throwable's self-suppression contract.
    private static void suppressCleanupFailure(Throwable primary, Throwable failure) {
        if (primary != null && primary != failure) {
            primary.addSuppressed(failure);
        }
    }

    private void retained(String reason) {
        LOGGER.warning("Retained YAML temporary " + temporary + ": " + reason
                + ". Stop competing writers and inspect it manually; never assume it is authoritative");
    }

    interface Operations {
        default BasicFileAttributes basic(Path path) throws IOException {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }

        default PosixFileAttributes posix(Path path) throws IOException {
            PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            return view == null ? null : view.readAttributes();
        }

        default boolean hasAcl(Path path) throws IOException {
            return Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null;
        }

        default boolean writable(Path path) {
            return Files.isWritable(path);
        }

        default String nonce() {
            return UUID.randomUUID().toString().replace("-", "");
        }

        default FileChannel open(Path path) throws IOException {
            return FileChannel.open(path, new HashSet<>(Arrays.asList(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }

        default void write(FileChannel channel, ByteBuffer bytes) throws IOException {
            channel.write(bytes);
        }

        default void permissions(Path path, Set<PosixFilePermission> mode) throws IOException {
            Files.setPosixFilePermissions(path, mode);
        }

        default void owner(Path path, UserPrincipal owner) throws IOException {
            Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).setOwner(owner);
        }

        default void group(Path path, GroupPrincipal group) throws IOException {
            Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).setGroup(group);
        }

        default void force(FileChannel channel) throws IOException {
            channel.force(true);
        }

        default void close(FileChannel channel) throws IOException {
            channel.close();
        }

        default void move(Path source, Path target) throws IOException {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }

        default void link(Path target, Path completed) throws IOException {
            Files.createLink(target, completed);
        }

        default void delete(Path path) throws IOException {
            Files.delete(path);
        }

        default void syncDirectory(Path path) throws IOException {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
    }
}
