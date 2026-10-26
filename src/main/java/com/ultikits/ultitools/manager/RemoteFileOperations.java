package com.ultikits.ultitools.manager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.utils.ContractPrimitives;
import com.ultikits.ultitools.utils.PayloadReader;
import com.ultikits.ultitools.utils.PosixAttributePreserver;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.CloudRequestHandler;

/**
 * Answers the UltiCloud protocol 2.0.0 {@code files.*} requests for one connection; internal, not
 * for module use.
 *
 * <p>Every request resolves its path through {@link FileOperationManager#resolveTarget} unchanged,
 * so the whole 6.3.0 guard set applies to protocol v2 exactly as it applied to v1: the editable
 * roots, the protected server files and extensions, the credential basenames and glob patterns,
 * the remote action log's directory and (since plan 22-07) the framework's own
 * {@code .ultikits/} state directory. This class never builds a {@link File} from a request path
 * on its own; it acts only on the operand the guard returns.</p>
 *
 * <p><b>Every file decision is recorded.</b> Protocol v1 recorded one {@code RemoteActionLog}
 * entry per remote file operation, reads and listings included (phase 6 D-22); this handler keeps
 * that property for all four requests: one entry per request that reached a decision, ALLOWED when
 * the request was carried out (the file read, the directory listed, the change made) and DENIED
 * when it was answered with an error code, with the path as the target (empty for the root
 * listing), the literal {@code "panel"} as the actor and — for a denial — exactly the text the
 * panel received. A malformed payload is answered by the router before it reaches a decision and
 * records nothing. Content is never recorded. The log itself is injected, so the handler stays
 * testable and the wiring (plan 22-21) binds it to the live {@code RemoteActionLog}. Reads keep
 * being answered while the server disables; one answered after the action log closed leaves no
 * line, which loses no change because a read changes nothing.</p>
 *
 * <p>It is package-private on purpose: {@code resolveTarget} and {@code ResolvedTarget} are
 * package-private, and keeping the handler in this package is what makes calling them — rather
 * than reimplementing a second, weaker path walk — the only possible way to answer a request.</p>
 *
 * <p>Results are the contract's {@code *.result} payloads: {@code status: ok} with the typed data,
 * or {@code status: error} whose {@code code} is one of the codes the vendored schema lists for
 * that result type. Payload validation failures are not answered here; they leave
 * {@link PayloadReader} as an unchecked {@code invalid_message} that
 * {@link com.ultikits.ultitools.utils.AgentMessageRouter} turns into a correlated protocol error
 * before any filesystem access happens.</p>
 *
 * <p><b>Why this path cannot overwrite operator content.</b> {@code files.write} replaces a file
 * only while the {@code baseVersion} the panel sent still equals the SHA-256 of the bytes on disk,
 * and that comparison and the replacement happen under one JVM-wide lock. A version that matches
 * means the panel is writing back exactly the bytes it read, so the operator's content is changed
 * only by an explicit panel edit of that content. Disabling the write path leaves every file
 * untouched. A writer outside this framework that changes the file in the window between the
 * comparison and the atomic move is a niche case (the operator editing server files while the
 * panel writes the same file) and is not defended further: the next read reports the version the
 * file then has, and the panel's next write is compared against it again.</p>
 */
final class RemoteFileOperations implements CloudRequestHandler {
    private static final Logger LOGGER = Logger.getLogger(RemoteFileOperations.class.getName());

    /**
     * Serializes the version comparison and the replacement of every remote write in this JVM.
     * One lock for the whole surface, so two writes of the same file — from this connection or any
     * other — cannot both pass the comparison: the second one reads the winner's bytes and
     * answers {@code file_changed}.
     */
    private static final Object WRITE_LOCK = new Object();

    private static final String FILES_LIST = "files.list";
    private static final String FILES_LIST_RESULT = "files.list.result";
    private static final String FILES_READ = "files.read";
    private static final String FILES_READ_RESULT = "files.read.result";
    private static final String FILES_WRITE = "files.write";
    private static final String FILES_WRITE_RESULT = "files.write.result";
    private static final String FILES_DELETE = "files.delete";
    private static final String FILES_DELETE_RESULT = "files.delete.result";

    /** The contract's upper bound on a result message, in code points. */
    private static final int MAX_MESSAGE_LENGTH = 500;

    /** The contract's upper bound on a {@code path} field, in code points. */
    private static final int MAX_PATH_LENGTH = 512;

    /** The contract's upper bound on a {@code content} field, in code points. */
    private static final int MAX_CONTENT_LENGTH = 393216;

    /** The contract's cap on one directory listing; above it the reply is truncated. */
    private static final int MAX_LIST_ENTRIES = 2000;

    /** {@code files.list} accepts the empty root path in addition to a {@code ServerPath}. */
    private static final Pattern LIST_PATH = Pattern.compile("^$|" + ContractPrimitives.SERVER_PATH.pattern());

    /** The action-log actor for this surface: protocol v2 carries no per-operator identity. */
    private static final String PANEL_ACTOR = "panel";

    /** The refusal text every {@code file_changed} answer carries. */
    private static final String CHANGED_MESSAGE =
            "The file changed after the panel read it; reload it and try again.";

    private static final Comparator<File> BY_NAME = (left, right) -> left.getName().compareTo(right.getName());

    private final FileOperationManager files;
    private final File serverRoot;
    private final Consumer<RemoteActionLog.Entry> actionLog;

    /** Test seam; see {@link #setWriteGate(Runnable)}. */
    private volatile Runnable writeGate;

    /**
     * Creates the handler for one server.
     *
     * @param files      the v1 file manager whose guard and editable-root configuration this
     *                   handler reuses rather than duplicating
     * @param serverRoot the server's root directory, used to name the editable roots
     * @param actionLog  receives one entry per decided request, or {@code null} when the
     *                   caller has no action log to write to (tests and headless harnesses)
     */
    RemoteFileOperations(FileOperationManager files, File serverRoot,
                         Consumer<RemoteActionLog.Entry> actionLog) {
        this.files = files;
        this.serverRoot = serverRoot;
        this.actionLog = actionLog;
    }

    /**
     * Installs a hook that runs inside the write lock immediately before the version comparison.
     * It exists so a test can hold one writer inside the critical section while a second writer
     * waits for the lock; production leaves it null, which costs one null check per write.
     *
     * @param gate the hook to run, or null to remove it
     */
    void setWriteGate(Runnable gate) {
        this.writeGate = gate;
    }

    @Override
    public void handle(CloudRequest request, CloudChannel channel) {
        String type = request.type();
        if (FILES_READ.equals(type)) {
            read(request, channel);
        } else if (FILES_LIST.equals(type)) {
            list(request, channel);
        } else if (FILES_WRITE.equals(type)) {
            write(request, channel);
        } else if (FILES_DELETE.equals(type)) {
            delete(request, channel);
        } else {
            throw new IllegalArgumentException("Unhandled files message type");
        }
    }

    // ---------------------------------------------------------------------------------------
    // files.read
    // ---------------------------------------------------------------------------------------

    /**
     * Answers {@code files.read}: the file's content decoded as strict UTF-8, its size in bytes and
     * the SHA-256 of exactly those bytes — the version a later {@code files.write} must send back
     * as its {@code baseVersion}.
     *
     * <p>The contract's RUNTIME_CHECKS {@code file-content} rule is applied to the raw bytes read
     * from disk, before decoding into a reply: a file above the negotiated {@code fileMaxBytes},
     * bytes that are not valid UTF-8, or a C0 control character other than TAB, LF and CR (and
     * DEL) answer {@code file_not_editable}. The rule exists so the panel never receives content
     * it cannot render and write back unchanged.</p>
     */
    private void read(CloudRequest request, CloudChannel channel) {
        PayloadReader reader = new PayloadReader(request.id(), request.payload())
                .keys(new String[]{"path"});
        String path = reader.string("path", 1, MAX_PATH_LENGTH, ContractPrimitives.SERVER_PATH);

        FileOperationManager.ResolvedTarget target = files.resolveTarget(path, FileOperationManager.ResolveMode.FOLLOW);
        if (!target.getDecision().isAllowed()) {
            refuseRead(channel, request.id(), path, "path_not_allowed", target.getDecision().getMessage());
            return;
        }

        File file = target.getOperand();
        if (!file.exists()) {
            refuseRead(channel, request.id(), path, "not_found", "The requested file does not exist.");
            return;
        }
        if (file.isDirectory()) {
            refuseRead(channel, request.id(), path, "is_directory", "The requested path is a directory.");
            return;
        }
        if (!file.isFile()) {
            refuseRead(channel, request.id(), path, "file_not_editable",
                    "The requested path is not a regular file.");
            return;
        }

        int maxBytes = channel.limits().fileMaxBytes();
        long size;
        try {
            size = Files.size(file.toPath());
        } catch (IOException unreadable) {
            LOGGER.log(Level.WARNING, "UltiCloud: a requested file could not be stat'ed ("
                    + unreadable.getClass().getSimpleName() + ").");
            refuseRead(channel, request.id(), path, "internal_error",
                    "The requested file could not be read.");
            return;
        }
        if (size > maxBytes) {
            refuseRead(channel, request.id(), path, "file_not_editable",
                    "The file is larger than this connection's file limit.");
            return;
        }

        byte[] bytes;
        try {
            bytes = readAtMost(file, maxBytes);
        } catch (IOException unreadable) {
            LOGGER.log(Level.WARNING, "UltiCloud: a requested file could not be read ("
                    + unreadable.getClass().getSimpleName() + ").");
            refuseRead(channel, request.id(), path, "internal_error",
                    "The requested file could not be read.");
            return;
        }

        String refusal = contentRefusal(bytes, maxBytes);
        if (refusal != null) {
            refuseRead(channel, request.id(), path, "file_not_editable", refusal);
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty("path", path);
        data.addProperty("content", decodeUtf8(bytes));
        data.addProperty("version", sha256Hex(bytes));
        data.addProperty("sizeBytes", bytes.length);
        auditAllowed(Capability.FILE_READ, FILES_READ, path);
        channel.replyOk(request.id(), FILES_READ_RESULT, data);
    }

    // ---------------------------------------------------------------------------------------
    // files.list
    // ---------------------------------------------------------------------------------------

    /**
     * Answers {@code files.list}: one directory's entries, with the size and modification time of
     * files ({@code null} for directories), or — for the empty path — the configured editable
     * roots, which is what the contract means by the server root: the panel may browse no further
     * up than the operator's grant.
     *
     * <p>A child whose own path the guard refuses (a credential file, a {@code .jar}, a link whose
     * real target lies outside the roots) is left out of the listing rather than marked, because a
     * v2 listing carries no per-entry refusal marker; the panel therefore never learns that such
     * a name exists. Above 2000 entries the reply keeps the first 2000 by name and sets
     * {@code truncated}, so one directory cannot produce an oversized frame.</p>
     */
    private void list(CloudRequest request, CloudChannel channel) {
        PayloadReader reader = new PayloadReader(request.id(), request.payload())
                .keys(new String[]{"path"});
        String path = reader.string("path", 0, MAX_PATH_LENGTH, LIST_PATH);

        JsonArray entries = new JsonArray();
        boolean truncated;
        if (path.isEmpty()) {
            truncated = appendEditableRoots(entries);
        } else {
            FileOperationManager.ResolvedTarget target =
                    files.resolveTarget(path, FileOperationManager.ResolveMode.FOLLOW);
            if (!target.getDecision().isAllowed()) {
                refuseList(channel, request.id(), path, "path_not_allowed",
                        target.getDecision().getMessage());
                return;
            }
            File dir = target.getOperand();
            if (!dir.exists()) {
                refuseList(channel, request.id(), path, "not_found", "The requested directory does not exist.");
                return;
            }
            if (!dir.isDirectory()) {
                refuseList(channel, request.id(), path, "not_a_directory",
                        "The requested path is not a directory.");
                return;
            }
            File[] children = dir.listFiles();
            if (children == null) {
                refuseList(channel, request.id(), path, "internal_error",
                        "The requested directory could not be read.");
                return;
            }
            List<File> allowed = allowedChildren(path, children);
            Collections.sort(allowed, BY_NAME);
            truncated = allowed.size() > MAX_LIST_ENTRIES;
            for (int i = 0; i < Math.min(allowed.size(), MAX_LIST_ENTRIES); i++) {
                entries.add(entryOf(allowed.get(i)));
            }
        }

        JsonObject data = new JsonObject();
        data.addProperty("path", path);
        data.add("entries", entries);
        data.addProperty("truncated", truncated);
        auditAllowed(Capability.FILE_READ, FILES_LIST, path);
        channel.replyOk(request.id(), FILES_LIST_RESULT, data);
    }

    /**
     * Lists the configured editable roots as directory entries named by their server-root-relative
     * path — the very spelling a subsequent {@code files.list} of that name needs. A root that is
     * outside the server root, or whose name the guard refuses, cannot be addressed by any valid
     * {@code ServerPath} and is skipped: listing it would offer the panel a name that every later
     * request refuses.
     *
     * @param entries receives one entry per addressable root
     * @return whether the contract's 2000-entry cap was exceeded
     */
    private boolean appendEditableRoots(JsonArray entries) {
        List<File> roots = files.getEditableRoots();
        int appended = 0;
        for (File root : roots) {
            String name = relativeToServerRoot(root);
            if (name == null || !ContractPrimitives.SERVER_PATH.matcher(name).matches()) {
                LOGGER.fine("UltiCloud: an editable root is not addressable from the server root and is not listed.");
                continue;
            }
            if (!files.isPathAllowed(name).isAllowed()) {
                LOGGER.fine("UltiCloud: an editable root is refused by the guard and is not listed.");
                continue;
            }
            if (appended >= MAX_LIST_ENTRIES) {
                return true;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("name", name);
            entry.addProperty("type", "dir");
            entry.add("sizeBytes", JsonNull.INSTANCE);
            entry.add("modifiedAt", JsonNull.INSTANCE);
            entries.add(entry);
            appended++;
        }
        return false;
    }

    private String relativeToServerRoot(File root) {
        Path base = serverRoot.toPath().toAbsolutePath().normalize();
        Path target = root.toPath().toAbsolutePath().normalize();
        String relative = base.relativize(target).toString();
        if (relative.isEmpty()) {
            return "";
        }
        if (relative.startsWith("..")) {
            return null;
        }
        return relative.replace(File.separatorChar, '/');
    }

    private List<File> allowedChildren(String basePath, File[] children) {
        List<File> allowed = new ArrayList<>();
        String prefix = basePath.isEmpty() ? "" : basePath + "/";
        for (File child : children) {
            String childPath = prefix + child.getName();
            if (files.resolveTarget(childPath, FileOperationManager.ResolveMode.FOLLOW)
                    .getDecision().isAllowed()) {
                allowed.add(child);
            }
        }
        return allowed;
    }

    private static JsonObject entryOf(File child) {
        boolean directory = child.isDirectory();
        JsonObject entry = new JsonObject();
        entry.addProperty("name", child.getName());
        entry.addProperty("type", directory ? "dir" : "file");
        if (directory) {
            entry.add("sizeBytes", JsonNull.INSTANCE);
            entry.add("modifiedAt", JsonNull.INSTANCE);
        } else {
            entry.addProperty("sizeBytes", child.length());
            entry.addProperty("modifiedAt", child.lastModified());
        }
        return entry;
    }

    // ---------------------------------------------------------------------------------------
    // files.write
    // ---------------------------------------------------------------------------------------

    /**
     * Answers {@code files.write}: replaces an existing file's content when, and only when, the
     * panel's {@code baseVersion} still equals the SHA-256 of the bytes on disk.
     *
     * <p>The comparison and the atomic replacement happen under one JVM-wide lock, so concurrent
     * writers cannot both succeed. The replacement goes through
     * {@link PosixAttributePreserver#replaceInPlace}, which keeps the file's POSIX permissions,
     * owner and group and refuses to write through a symbolic link or over an operator-pinned
     * read-only file ({@code write_denied}). The write never creates a file.</p>
     */
    private void write(CloudRequest request, CloudChannel channel) {
        PayloadReader reader = new PayloadReader(request.id(), request.payload())
                .keys(new String[]{"path", "content", "baseVersion"});
        String path = reader.string("path", 1, MAX_PATH_LENGTH, ContractPrimitives.SERVER_PATH);
        String content = reader.string("content", 0, MAX_CONTENT_LENGTH, null);
        String baseVersion = reader.string("baseVersion", 64, 64, ContractPrimitives.SHA256_HEX);

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String refusal = contentRefusal(bytes, channel.limits().fileMaxBytes());
        if (refusal != null) {
            refuseWrite(channel, request.id(), path, "file_not_editable", refusal);
            return;
        }
        if (!decodeUtf8(bytes).equals(content)) {
            refuseWrite(channel, request.id(), path, "file_not_editable",
                    "The content is not representable as UTF-8.");
            return;
        }

        FileOperationManager.ResolvedTarget target = files.resolveTarget(path, FileOperationManager.ResolveMode.FOLLOW);
        if (!target.getDecision().isAllowed()) {
            refuseWrite(channel, request.id(), path, "path_not_allowed", target.getDecision().getMessage());
            return;
        }

        WriteOutcome outcome = writeLocked(target.getOperand(), bytes, baseVersion,
                channel.limits().fileMaxBytes());
        if (outcome.code != null) {
            refuseWrite(channel, request.id(), path, outcome.code, outcome.message);
            return;
        }

        auditAllowed(Capability.FILE_WRITE, FILES_WRITE, path);
        JsonObject data = new JsonObject();
        data.addProperty("path", path);
        data.addProperty("version", sha256Hex(bytes));
        data.addProperty("sizeBytes", bytes.length);
        channel.replyOk(request.id(), FILES_WRITE_RESULT, data);
    }

    /**
     * The critical section of {@code files.write}: compare the version against the bytes on disk
     * and replace the file, both under {@link #WRITE_LOCK}.
     *
     * <p>A current file above the connection's {@code fileMaxBytes} is answered {@code file_changed}
     * without being read: the panel can never have received such a file's content, so no
     * {@code baseVersion} it holds can equal that file's bytes. Refusing it here also keeps a large
     * log or plugin database from being loaded into the heap merely to compare a hash.</p>
     *
     * @param file        the resolved target the guard allowed
     * @param content     the exact bytes to write
     * @param baseVersion the version the panel read
     * @param maxBytes    the connection's {@code fileMaxBytes}
     * @return an empty outcome when the file now holds {@code content}, otherwise the refusal
     */
    private WriteOutcome writeLocked(File file, byte[] content, String baseVersion, int maxBytes) {
        synchronized (WRITE_LOCK) {
            Runnable gate = writeGate;
            if (gate != null) {
                gate.run();
            }
            if (!file.exists()) {
                return new WriteOutcome("not_found", "The requested file does not exist.");
            }
            if (file.isDirectory()) {
                return new WriteOutcome("is_directory", "The requested path is a directory.");
            }
            if (!file.isFile()) {
                return new WriteOutcome("file_not_editable", "The requested path is not a regular file.");
            }
            byte[] current;
            try {
                if (Files.size(file.toPath()) > maxBytes) {
                    return new WriteOutcome("file_changed", CHANGED_MESSAGE);
                }
                current = readAtMost(file, maxBytes);
            } catch (IOException unreadable) {
                LOGGER.log(Level.WARNING, "UltiCloud: a write target could not be read ("
                        + unreadable.getClass().getSimpleName() + ").");
                return new WriteOutcome("internal_error", "The requested file could not be read.");
            }
            if (current.length > maxBytes) {
                return new WriteOutcome("file_changed", CHANGED_MESSAGE);
            }
            if (!sha256Hex(current).equals(baseVersion)) {
                return new WriteOutcome("file_changed", CHANGED_MESSAGE);
            }
            WriteDenial denial = new WriteDenial();
            if (!PosixAttributePreserver.replaceInPlace(file, content, denial)) {
                return new WriteOutcome("write_denied", denial.reason());
            }
            return new WriteOutcome(null, null);
        }
    }

    // ---------------------------------------------------------------------------------------
    // files.delete
    // ---------------------------------------------------------------------------------------

    /**
     * Answers {@code files.delete}: removes one file, or one symbolic link as the link itself. A
     * directory is refused with {@code is_directory} — the contract has no recursive delete, and a
     * panel delete must never remove a tree an operator built. The entry is resolved in entry mode
     * ({@link FileOperationManager.ResolveMode#ENTRY}), so a link to a directory is judged and
     * removed as the link, never followed.
     */
    private void delete(CloudRequest request, CloudChannel channel) {
        PayloadReader reader = new PayloadReader(request.id(), request.payload())
                .keys(new String[]{"path"});
        String path = reader.string("path", 1, MAX_PATH_LENGTH, ContractPrimitives.SERVER_PATH);

        FileOperationManager.ResolvedTarget target = files.resolveTarget(path, FileOperationManager.ResolveMode.ENTRY);
        if (!target.getDecision().isAllowed()) {
            refuseDelete(channel, request.id(), path, "path_not_allowed", target.getDecision().getMessage());
            return;
        }

        Path entry = target.getOperand().toPath();
        if (!Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) {
            refuseDelete(channel, request.id(), path, "not_found", "The requested file does not exist.");
            return;
        }
        if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
            refuseDelete(channel, request.id(), path, "is_directory",
                    "The requested path is a directory; directories are never deleted remotely.");
            return;
        }
        try {
            Files.delete(entry);
        } catch (IOException notDeleted) {
            LOGGER.log(Level.WARNING, "UltiCloud: a requested file could not be deleted ("
                    + notDeleted.getClass().getSimpleName() + ").");
            refuseDelete(channel, request.id(), path, "write_denied",
                    "The requested file could not be deleted.");
            return;
        }
        auditAllowed(Capability.FILE_DELETE, FILES_DELETE, path);
        channel.replyOk(request.id(), FILES_DELETE_RESULT, new JsonObject());
    }

    // ---------------------------------------------------------------------------------------
    // shared helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Reads a file through a stream capped at {@code maxBytes + 1} bytes.
     *
     * <p>The extra byte is how a file that grew between the caller's size check and this read is
     * still detected: the result is then one byte longer than the limit and the caller's existing
     * content rule refuses it. Without the cap a file of any size would be loaded into the heap
     * before the limit could be applied — the reason this helper exists at all.</p>
     *
     * @param file     the file to read
     * @param maxBytes the connection's {@code fileMaxBytes}
     * @return the file's bytes, or {@code maxBytes + 1} of them when the file is larger
     * @throws IOException if the file cannot be opened or read
     */
    private static byte[] readAtMost(File file, int maxBytes) throws IOException {
        long cap = (long) maxBytes + 1L;
        ByteArrayOutputStream collected =
                new ByteArrayOutputStream((int) Math.min(cap, 8192L));
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[8192];
            long total = 0L;
            while (total < cap) {
                int wanted = (int) Math.min(buffer.length, cap - total);
                int read = input.read(buffer, 0, wanted);
                if (read == -1) {
                    break;
                }
                collected.write(buffer, 0, read);
                total += read;
            }
        }
        return collected.toByteArray();
    }

    /**
     * The contract's {@code file-content} rule over raw bytes.
     *
     * @param bytes    the bytes that would be sent, or written, as file content
     * @param maxBytes the negotiated {@code fileMaxBytes}
     * @return why the bytes are not editable content, or {@code null} when they are
     */
    static String contentRefusal(byte[] bytes, int maxBytes) {
        if (bytes.length > maxBytes) {
            return "The file is larger than this connection's file limit.";
        }
        String text;
        try {
            text = decodeUtf8(bytes);
        } catch (IllegalArgumentException notUtf8) {
            return "The file is not valid UTF-8.";
        }
        for (int i = 0; i < text.length(); i++) {
            if (isForbiddenControl(text.charAt(i))) {
                return "The file contains a control character.";
            }
        }
        return null;
    }

    private static boolean isForbiddenControl(char character) {
        if (character == '\t' || character == '\n' || character == '\r') {
            return false;
        }
        return character < 0x20 || character == 0x7F;
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notUtf8) {
            throw new IllegalArgumentException("The bytes are not valid UTF-8", notUtf8);
        }
    }

    static String sha256Hex(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder(64);
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException unattainable) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", unattainable);
        }
    }

    /**
     * Sends a correlated error for one result type. The message is the guard's own refusal text
     * where one exists; it is capped at the contract's 500 code points because a schema-violating
     * frame would be dropped by the gateway instead of explaining the refusal to the operator.
     */
    private static void error(CloudChannel channel, String replyTo, String type, String code, String message) {
        channel.replyError(replyTo, type, code, capped(message), null);
    }

    /**
     * Answers a refused {@code files.write} and records the same text in the action log, so the
     * log says what the operator was told.
     */
    private void refuseWrite(CloudChannel channel, String replyTo, String path, String code, String message) {
        String reason = capped(message);
        error(channel, replyTo, FILES_WRITE_RESULT, code, reason);
        auditDenied(Capability.FILE_WRITE, FILES_WRITE, path, reason);
    }

    /**
     * Answers a refused {@code files.read} and records it, as {@link #refuseWrite} does. The entry is written
     * before the reply, so whoever sees the answer also finds the line.
     */
    private void refuseRead(CloudChannel channel, String replyTo, String path, String code, String message) {
        String reason = capped(message);
        auditDenied(Capability.FILE_READ, FILES_READ, path, reason);
        error(channel, replyTo, FILES_READ_RESULT, code, reason);
    }

    /** Answers a refused {@code files.list} and records it, as {@link #refuseRead} does. */
    private void refuseList(CloudChannel channel, String replyTo, String path, String code, String message) {
        String reason = capped(message);
        auditDenied(Capability.FILE_READ, FILES_LIST, path, reason);
        error(channel, replyTo, FILES_LIST_RESULT, code, reason);
    }

    /** Answers a refused {@code files.delete} and records it, as {@link #refuseWrite} does. */
    private void refuseDelete(CloudChannel channel, String replyTo, String path, String code, String message) {
        String reason = capped(message);
        error(channel, replyTo, FILES_DELETE_RESULT, code, reason);
        auditDenied(Capability.FILE_DELETE, FILES_DELETE, path, reason);
    }

    private void auditAllowed(Capability capability, String action, String target) {
        record(RemoteActionLog.Entry.allowed(capability, action, target, PANEL_ACTOR));
    }

    private void auditDenied(Capability capability, String action, String target, String reason) {
        record(RemoteActionLog.Entry.denied(capability, action, target, PANEL_ACTOR, reason));
    }

    /**
     * Hands one entry to the action log. A missing sink is a silent no-op (tests, headless
     * harnesses), and a sink that throws must not cost the panel its reply — the request itself
     * has already been decided.
     */
    private void record(RemoteActionLog.Entry entry) {
        Consumer<RemoteActionLog.Entry> sink = actionLog;
        if (sink == null) {
            return;
        }
        try {
            sink.accept(entry);
        } catch (RuntimeException unwritable) {
            LOGGER.log(Level.WARNING, "UltiCloud: an action-log entry could not be recorded ("
                    + unwritable.getClass().getSimpleName() + ").");
        }
    }

    /** The refusal text as the panel receives it: never empty and never above the contract's cap. */
    private static String capped(String message) {
        String safe = message == null || message.isEmpty() ? "The request was refused." : message;
        if (safe.codePointCount(0, safe.length()) > MAX_MESSAGE_LENGTH) {
            safe = safe.substring(0, safe.offsetByCodePoints(0, MAX_MESSAGE_LENGTH));
        }
        return safe;
    }

    /** One refusal from the locked write section: a contract error code and its message. */
    private static final class WriteOutcome {
        private final String code;
        private final String message;

        private WriteOutcome(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    /**
     * Collects why {@link PosixAttributePreserver#replaceInPlace} declined the replacement.
     * A permission-copy failure is not a refusal — the shared operation still proceeds — so it
     * leaves the reason unset.
     */
    private static final class WriteDenial implements PosixAttributePreserver.ReplaceInPlaceListener {
        private String reason;

        private String reason() {
            return reason == null ? "The file could not be written." : reason;
        }

        @Override
        public void onOperatorPinnedReadOnly() {
            reason = "The file is read-only by its permissions.";
        }

        @Override
        public void onSymbolicLink() {
            reason = "The path is a symbolic link.";
        }

        @Override
        public void onSourceAttributesUnreadable() {
            reason = "The file's attributes could not be read.";
        }

        @Override
        public void onPermissionCopyFailure() {
            reason = null;
        }

        @SuppressWarnings("PMD.UnusedFormalParameter") // the account names are not repeated to the panel
        @Override
        public void onOwnershipCopyFailure(String ownerName, String groupName) {
            reason = "The file's owner or group could not be preserved.";
        }

        @Override
        public void onWriteFailure(IOException cause) {
            reason = "The file could not be replaced.";
        }
    }
}
