package com.ultikits.ultitools.manager;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.utils.ContractPrimitives;
import com.ultikits.ultitools.utils.PayloadReader;
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
 */
final class RemoteFileOperations implements CloudRequestHandler {
    private static final Logger LOGGER = Logger.getLogger(RemoteFileOperations.class.getName());

    private static final String FILES_READ = "files.read";
    private static final String FILES_READ_RESULT = "files.read.result";

    /** The contract's upper bound on a result message, in code points. */
    private static final int MAX_MESSAGE_LENGTH = 500;

    /** The contract's upper bound on a {@code path} field, in code points. */
    private static final int MAX_PATH_LENGTH = 512;

    private final FileOperationManager files;
    private final File serverRoot;

    /**
     * Creates the handler for one server.
     *
     * @param files      the v1 file manager whose guard and editable-root configuration this
     *                   handler reuses rather than duplicating
     * @param serverRoot the server's root directory, used to name the editable roots
     */
    RemoteFileOperations(FileOperationManager files, File serverRoot) {
        this.files = files;
        this.serverRoot = serverRoot;
    }

    @Override
    public void handle(CloudRequest request, CloudChannel channel) {
        if (FILES_READ.equals(request.type())) {
            read(request, channel);
            return;
        }
        throw new IllegalArgumentException("Unhandled files message type");
    }

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

        FileOperationManager.ResolvedTarget target =
                files.resolveTarget(path, FileOperationManager.ResolveMode.FOLLOW);
        if (!target.getDecision().isAllowed()) {
            error(channel, request.id(), FILES_READ_RESULT, "path_not_allowed", target.getDecision().getMessage());
            return;
        }

        File file = target.getOperand();
        if (!file.exists()) {
            error(channel, request.id(), FILES_READ_RESULT, "not_found", "The requested file does not exist.");
            return;
        }
        if (file.isDirectory()) {
            error(channel, request.id(), FILES_READ_RESULT, "is_directory", "The requested path is a directory.");
            return;
        }
        if (!file.isFile()) {
            error(channel, request.id(), FILES_READ_RESULT, "file_not_editable",
                    "The requested path is not a regular file.");
            return;
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.toPath());
        } catch (IOException unreadable) {
            LOGGER.log(Level.WARNING, "UltiCloud: a requested file could not be read ("
                    + unreadable.getClass().getSimpleName() + ").");
            error(channel, request.id(), FILES_READ_RESULT, "internal_error",
                    "The requested file could not be read.");
            return;
        }

        String refusal = contentRefusal(bytes, channel.limits().fileMaxBytes());
        if (refusal != null) {
            error(channel, request.id(), FILES_READ_RESULT, "file_not_editable", refusal);
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty("path", path);
        data.addProperty("content", decodeUtf8(bytes));
        data.addProperty("version", sha256Hex(bytes));
        data.addProperty("sizeBytes", bytes.length);
        channel.replyOk(request.id(), FILES_READ_RESULT, data);
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
            char character = text.charAt(i);
            if (isForbiddenControl(character)) {
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
        String safe = message == null || message.isEmpty() ? "The request was refused." : message;
        if (safe.codePointCount(0, safe.length()) > MAX_MESSAGE_LENGTH) {
            safe = safe.substring(0, safe.offsetByCodePoints(0, MAX_MESSAGE_LENGTH));
        }
        channel.replyError(replyTo, type, code, safe, null);
    }
}
