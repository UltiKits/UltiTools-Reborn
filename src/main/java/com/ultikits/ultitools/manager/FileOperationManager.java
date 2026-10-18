package com.ultikits.ultitools.manager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.AccessDecision;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.utils.CredentialStore;
import com.ultikits.ultitools.utils.FrameworkText;
import com.ultikits.ultitools.utils.PluginInitiationUtils;
import com.ultikits.ultitools.websocket.PanelConnectionLog;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;
import org.jetbrains.annotations.ApiStatus;

/**
 * File operation manager.
 * Handles file operation requests received over the WebSocket connection.
 *
 * <p>The remote file API is confined to two layers (D-14): an unconditional, non-configurable
 * deny layer for credential-bearing files and the framework's own action-log directory, and an
 * operator-configured editable-root set (D-15) that answers "where" once the deny layer has
 * already answered "not this, ever". See {@link #isPathAllowed(String)} for the ordering
 * rationale.
 *
 * <p>Those two layers are the lexical first step only. Every operation then takes its final
 * decision and its I/O operand from one resolver, {@link #resolveTarget(String, ResolveMode)},
 * which resolves the request against the real filesystem and checks real containment, so the
 * object that is authorized is the object that read, write, list and delete actually reach.
 */
@ApiStatus.Internal
public class FileOperationManager {
    private UltiPanelWebSocketClient webSocketClient;
    private final File serverRoot;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    private static final Set<String> BLOCKED_FILES = new HashSet<String>(Arrays.asList(
        "server.properties", "ops.json", "whitelist.json",
        "banned-ips.json", "banned-players.json", "eula.txt",
        "usercache.json", "bukkit.yml", "spigot.yml",
        "paper.yml", "paper-global.yml", "paper-world-defaults.yml"
    ));

    private static final Set<String> BLOCKED_EXTENSIONS = new HashSet<String>(Arrays.asList(
        ".jar", ".sh", ".bat", ".exe", ".class"
    ));

    /**
     * D-16's basename glob patterns for credential-bearing files. Patterns, not an enumeration:
     * a credential file added later is caught without anyone remembering to edit a {@link Set}.
     * An exact-name set is only ever as complete as the last person who thought about it; a
     * pattern does not depend on that. Compiled once via
     * {@code FileSystems.getDefault().getPathMatcher(String)} rather than hand-rolled wildcard
     * matching.
     */
    private static final List<PathMatcher> DENY_GLOB_MATCHERS = buildDenyGlobMatchers();

    /**
     * D-16's exact-basename credential set — matched case-insensitively, see {@link #basenameOf}.
     * <p>
     * <b>Plan 08-15 checkpoint decision ({@code outside-roots-keep-filename}):</b> the credential
     * file moved outside every default editable root (D-15) to
     * {@code <server root>/.ultikits/credentials.json} — the remote file surface cannot reach it
     * by path at all any more, so this entry stops being the *only* protection. It is kept
     * anyway, referencing {@link CredentialStore#CREDENTIAL_FILE_NAME} rather than a duplicated
     * literal, as defence in depth: it costs nothing, and it still protects an operator who
     * widens the editable roots to include the new location's parent. {@code "data.json"} stays
     * in this set too — not dead weight, but the pre-6.3.0 name, still live at its old location
     * for any install that has not yet restarted onto a migrated {@code CredentialStore}.
     */
    private static final Set<String> DENY_EXACT_BASENAMES = new HashSet<String>(Arrays.asList(
        "data.json", CredentialStore.CREDENTIAL_FILE_NAME, "secring.gpg", "access_key.txt", ".dev.vars"
    ));

    /**
     * The relative location of the remote action log's own directory (D-23/D-31), expressed as a
     * lexical {@link Path} so containment is checked by name element rather than string prefix —
     * a sibling directory named {@code security-backup} must not be caught by it.
     */
    private static final Path SECURITY_DIR_RELATIVE = Paths.get("plugins", "UltiTools", "security");

    /**
     * The {@link RemoteActionLog.Entry#getAction()} literal prefix every entry this class records
     * carries, extended with the operation itself (e.g. {@code file_operation:read}) — D-22.
     */
    private static final String ACTION_FILE_OPERATION_PREFIX = "file_operation:";

    /** D-15's config key for the operator-configured editable-root set. */
    private static final String EDITABLE_ROOTS_CONFIG_KEY = "ultipanel.files.editable-roots";

    /** D-15's shipped default: plugin configs and historical logs, nothing else. */
    private static final List<String> DEFAULT_EDITABLE_ROOTS =
            Collections.unmodifiableList(Arrays.asList("plugins", "logs"));

    /**
     * The operator-configured editable-root set (D-15), resolved to {@link File}s rooted at
     * {@link #serverRoot}. Not {@code final} — {@link #reloadRootsFromConfig()} may reassign it
     * without reconstructing the manager.
     */
    private List<File> editableRoots;

    // All field declarations precede all methods below this point (PMD
    // FieldDeclarationsShouldBeAtStartOfClass) — buildDenyGlobMatchers() used to sit between
    // DENY_EXACT_BASENAMES/SECURITY_DIR_RELATIVE and the four fields above; moved here so the
    // field block is contiguous.
    private static List<PathMatcher> buildDenyGlobMatchers() {
        List<String> globs = Arrays.asList("*.key", "*.pem", "*.p12", "*.jks", "*.keystore", ".env*");
        List<PathMatcher> matchers = new ArrayList<>();
        for (String glob : globs) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
        }
        return Collections.unmodifiableList(matchers);
    }

    public FileOperationManager() {
        this.serverRoot = new File(System.getProperty("user.dir"));
        reloadRootsFromConfig();
    }

    /**
     * Sets the WebSocket client.
     * @param client the WebSocket client
     */
    public void setWebSocketClient(UltiPanelWebSocketClient client) {
        this.webSocketClient = client;
    }

    /**
     * The operator-configured editable-root set this manager currently enforces.
     *
     * @return an unmodifiable view of the resolved root directories
     */
    public List<File> getEditableRoots() {
        return Collections.unmodifiableList(editableRoots);
    }

    /**
     * (Re)loads {@link #editableRoots} from {@code ultipanel.files.editable-roots}, mirroring
     * {@code ErrorReportCollector.loadConfiguration()}'s try/catch shape. Public so a future
     * config reload does not require reconstructing the manager.
     * <p>
     * An <b>absent</b> key resolves to the shipped default ({@code plugins}, {@code logs}); an
     * <b>explicit empty list</b> is honoured as the operator deliberately granting nothing —
     * collapsing those two cases would make "grant nothing" inexpressible.
     */
    public final void reloadRootsFromConfig() {
        List<String> roots = DEFAULT_EDITABLE_ROOTS;
        try {
            UltiTools instance = UltiTools.getInstance();
            if (instance != null && instance.getConfig().isSet(EDITABLE_ROOTS_CONFIG_KEY)) {
                roots = instance.getConfig().getStringList(EDITABLE_ROOTS_CONFIG_KEY);
            }
        } catch (Exception e) {
            roots = DEFAULT_EDITABLE_ROOTS;
            logWarning("Failed to load " + EDITABLE_ROOTS_CONFIG_KEY
                    + ", using shipped default (plugins, logs): " + e.getMessage());
        }

        List<File> resolved = new ArrayList<>();
        for (String root : roots) {
            resolved.add(new File(serverRoot, root));
        }
        this.editableRoots = Collections.unmodifiableList(resolved);
    }

    /**
     * Best-effort warning logging that never throws — {@link UltiTools#getInstance()} may be
     * {@code null} in a test harness with no live plugin.
     */
    private static void logWarning(String message) {
        try {
            UltiTools instance = UltiTools.getInstance();
            if (instance != null) {
                instance.getLogger().log(Level.WARNING, message);
            }
        } catch (Exception ignored) {
            // Best-effort logging only — never let a diagnostic warning break the caller.
        }
    }

    /**
     * Checks whether a file path may be reached through the remote file API, and — unlike the
     * {@code boolean} this returned before 6.3.0 — says <b>why not</b> when it is refused (D-17).
     * <p>
     * Two ordered layers. The unconditional deny layer ({@link #deniedUnconditionally(String)})
     * runs <b>first</b>; the editable-root set runs second. <b>Order is load-bearing:</b> a
     * credential file that happens to sit inside a granted editable root (e.g.
     * {@code plugins/UltiTools/data.json} with {@code plugins} granted) must report the
     * non-configurable cause — checking the root set first would report the wrong cause for
     * exactly that file.
     *
     * @param path the requested path, forward- or back-slash separated, with or without a
     *             leading slash
     * @return an {@link AccessDecision} carrying the refusal cause when denied
     */
    public AccessDecision isPathAllowed(String path) {
        if (path == null || path.trim().isEmpty()) {
            return AccessDecision.deniedNonConfigurable("path is null or empty");
        }

        String normalized = normalize(path);

        AccessDecision deny = deniedUnconditionally(normalized);
        if (deny != null) {
            return deny;
        }

        if (!isWithinEditableRoots(normalized)) {
            return AccessDecision.deniedConfigurable(
                    "'" + normalized + "' is outside the editable roots",
                    EDITABLE_ROOTS_CONFIG_KEY);
        }

        return AccessDecision.allowed();
    }

    /**
     * The unconditional deny layer (D-16). Reads no config key and consults no
     * {@code Capability} — nothing here can be configured open.
     * <p>
     * <b>Why this layer cannot be configured open:</b> authorizing the panel to manage files is
     * not authorizing it to hand out the key to the panel itself (D-01). A stolen refresh token
     * is persistent access an operator cannot revoke by changing a password, unlike a compromised
     * editable-root grant, which a config edit closes immediately.
     * <p>
     * <b>The credential file</b> holds the live UltiCloud access and refresh tokens
     * ({@code CloudAuthManager}), which is why D-19 names it explicitly rather than leaving it to
     * the glob patterns. As of plan 08-15 it lives at
     * {@code <server root>/.ultikits/credentials.json} — outside every default editable root
     * (D-15), so the editable-root layer below can no longer reach it at all, regardless of
     * configuration. This layer's basename entries are kept anyway as defence in depth for both
     * names: {@code "credentials.json"} (current, referencing
     * {@link com.ultikits.ultitools.utils.CredentialStore#CREDENTIAL_FILE_NAME}) and
     * {@code "data.json"} (the pre-6.3.0 name, still live at
     * {@code plugins/UltiTools/data.json} for any install that has not yet restarted onto a
     * migrated {@code CredentialStore}). Matched by exact basename, not by directory prefix, so a
     * credential file with either name anywhere else is caught too.
     * <p>
     * <b>The {@code plugins/UltiTools/security/} rule</b> is what lets the remote action log
     * ({@code RemoteActionLog}) live inside {@code getDataFolder()} — Bukkit convention intact —
     * instead of being physically relocated (D-23/D-31). Accepted cost: the log and the
     * credential files now share one trust anchor; a defect in this layer loses both.
     * <p>
     * Folds in the pre-6.3.0 {@link #BLOCKED_FILES}/{@link #BLOCKED_EXTENSIONS} sets so every
     * unconditional refusal produces the same non-configurable decision shape.
     *
     * @param normalizedPath the path already stripped of a leading slash, forward-slash separated
     * @return a denied, non-configurable {@link AccessDecision} if this layer refuses the path;
     *         {@code null} if this layer has no objection and the caller must still run the
     *         editable-root check
     */
    public AccessDecision deniedUnconditionally(String normalizedPath) {
        String fileName = basenameOf(normalizedPath);
        String fileNameLower = fileName.toLowerCase(Locale.ROOT);

        if (BLOCKED_FILES.contains(fileNameLower)) {
            return AccessDecision.deniedNonConfigurable("'" + fileName + "' is a protected server file");
        }
        for (String ext : BLOCKED_EXTENSIONS) {
            if (fileNameLower.endsWith(ext)) {
                return AccessDecision.deniedNonConfigurable("'" + fileName + "' has a protected extension");
            }
        }
        if (DENY_EXACT_BASENAMES.contains(fileNameLower)) {
            return AccessDecision.deniedNonConfigurable("'" + fileName + "' is a protected credential file");
        }
        if (matchesDenyGlob(fileNameLower)) {
            return AccessDecision.deniedNonConfigurable("'" + fileName + "' matches a protected credential pattern");
        }
        if (isUnderSecurityDirectory(normalizedPath)) {
            return AccessDecision.deniedNonConfigurable(
                    "path is inside the remote action log's own directory");
        }

        return null;
    }

    /**
     * D-18's two schema reason codes for a refused {@code list} entry. A configurable
     * {@link AccessDecision} (outside every editable root — an operator can flip it) maps to
     * {@code OUTSIDE_ROOTS}; a non-configurable one (the unconditional credential deny layer)
     * maps to {@code PROTECTED_CREDENTIAL}. These two strings are the whole of the wire-protocol
     * contract this plan adds to {@code file_operation_result} list entries — see the class
     * javadoc's D-14 note and Plan 06-04's {@code artifacts_produced}.
     *
     * @param decision a denied {@link AccessDecision} (never {@link AccessDecision#allowed()})
     * @return {@code "OUTSIDE_ROOTS"} or {@code "PROTECTED_CREDENTIAL"}
     */
    private static String reasonCodeFor(AccessDecision decision) {
        return decision.isConfigurable() ? "OUTSIDE_ROOTS" : "PROTECTED_CREDENTIAL";
    }

    private static boolean matchesDenyGlob(String fileNameLower) {
        Path fileNamePath = Paths.get(fileNameLower);
        for (PathMatcher matcher : DENY_GLOB_MATCHERS) {
            if (matcher.matches(fileNamePath)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnderSecurityDirectory(String normalizedPath) {
        Path candidate = Paths.get(normalizedPath).normalize();
        return candidate.startsWith(SECURITY_DIR_RELATIVE);
    }

    private static String basenameOf(String normalizedPath) {
        int idx = normalizedPath.lastIndexOf('/');
        return idx >= 0 ? normalizedPath.substring(idx + 1) : normalizedPath;
    }

    private static String normalize(String path) {
        String trimmed = path.trim().replace("\\", "/");
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }

    /**
     * Lexical containment check against {@link #editableRoots} — no filesystem access, safe to
     * call before any real file exists. This is only the resolver's first step;
     * {@link #resolveTarget(String, ResolveMode)} performs the real, symlink-aware resolution and
     * containment check that decides the object every operation actually reaches.
     */
    private boolean isWithinEditableRoots(String normalizedPath) {
        if (editableRoots.isEmpty()) {
            return false;
        }
        Path candidate = new File(serverRoot, normalizedPath).toPath().normalize();
        for (File root : editableRoots) {
            if (candidate.startsWith(root.toPath().normalize())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Handles a file operation request.
     * <p>
     * Dispatches to one of the four operation handlers inside a {@link CompletableFuture#runAsync}
     * hop, then records exactly one {@link RemoteActionLog} entry per operation (D-22) — at this
     * single point, not inside each of the four handlers, so the log's line count means "file
     * decisions" and not "handler entries". {@code operation}/{@code path}/{@code actor} are
     * captured into locals <b>before</b> the async hop and never re-read from
     * {@code operationData} afterward, following the immutable-string capture discipline
     * {@link TriggerContext} establishes — the mutable {@link JsonObject} itself is not safe to
     * re-read once handed to another thread.
     */
    public void handleFileOperation(JsonObject operationData) {
        try {
            String operation = operationData.has("operation") && !operationData.get("operation").isJsonNull()
                ? operationData.get("operation").getAsString() : null;
            String path = operationData.has("path") && !operationData.get("path").isJsonNull()
                ? operationData.get("path").getAsString() : null;
            String operationId = operationData.has("operationId") && !operationData.get("operationId").isJsonNull()
                ? operationData.get("operationId").getAsString() : null;
            String actor = operationData.has("executor") && !operationData.get("executor").isJsonNull()
                ? operationData.get("executor").getAsString() : "panel";

            UltiTools.getInstance().getLogger().log(Level.INFO,
                FrameworkText.format("收到文件操作请求: %s, 路径: %s (ID: %s)", operation, path, operationId));

            // Captured before the async hop — see this method's javadoc.
            String capturedOperation = operation;
            String capturedPath = path;
            String capturedOperationId = operationId;
            String capturedActor = actor;

            // Handle the file operation asynchronously
            CompletableFuture.runAsync(() -> {
                AccessDecision decision;
                switch (capturedOperation) {
                    case "read":
                        decision = handleReadOperation(capturedPath, operationData, capturedOperationId);
                        break;
                    case "write":
                        decision = handleWriteOperation(capturedPath, operationData, capturedOperationId);
                        break;
                    case "list":
                        decision = handleListOperation(capturedPath, operationData, capturedOperationId);
                        break;
                    case "delete":
                        decision = handleDeleteOperation(capturedPath, operationData, capturedOperationId);
                        break;
                    default:
                        sendFileOperationResult(capturedOperationId, capturedOperation, capturedPath, false,
                            "Unsupported operation: " + capturedOperation, null);
                        decision = null;
                }
                recordFileDecision(capturedOperation, capturedPath, capturedActor, decision);
            });

        } catch (Exception e) {
            String operationId = operationData.has("operationId") && !operationData.get("operationId").isJsonNull()
                ? operationData.get("operationId").getAsString() : null;
            UltiTools.getInstance().getLogger().log(Level.WARNING, FrameworkText.format("文件操作处理失败: %s", e.getMessage()));
            sendFileOperationResult(operationId, "unknown", "unknown", false,
                "Error processing file operation: " + e.getMessage(), null);
        }
    }

    /**
     * D-22's single action-log record point for the whole remote file API — called once per
     * operation from {@link #handleFileOperation}'s async task, after the dispatched handler has
     * run, so a refusal decided deep inside a handler (Task 3's missing-{@code recursive}-flag
     * case) is the decision this records, not an earlier, now-superseded verdict.
     * <p>
     * A {@code null} decision — the unsupported-operation branch — records nothing: an operation
     * this manager does not recognise is not a file <em>decision</em>. A {@code null}
     * {@link RemoteActionLog} (no live plugin, or a test harness) is also a silent no-op.
     *
     * @param operation the operation name as received, used verbatim to resolve the capability and
     *                   build the action string
     * @param path      the requested path as received — the action-log {@code target}
     * @param actor     the inbound {@code executor} field verbatim, or the literal {@code "panel"}
     * @param decision  the policy decision the dispatched handler reached, or {@code null} for an
     *                  unsupported operation
     */
    private void recordFileDecision(String operation, String path, String actor, AccessDecision decision) {
        if (decision == null) {
            return;
        }
        RemoteActionLog log = UltiTools.getInstance().getRemoteActionLog();
        if (log == null) {
            return;
        }
        Capability capability = PluginInitiationUtils.resolveFileOperationCapability(operation);
        String action = ACTION_FILE_OPERATION_PREFIX + operation;
        if (decision.isAllowed()) {
            log.record(RemoteActionLog.Entry.allowed(capability, action, path, actor));
        } else {
            log.record(RemoteActionLog.Entry.denied(capability, action, path, actor, decision.getMessage()));
        }
    }
    
    /**
     * Handles a file read operation.
     *
     * @return the {@link AccessDecision} that gated this read — the value
     *         {@link #recordFileDecision} records, regardless of whether the read then succeeded
     *         or failed for an unrelated reason (file not found, not a regular file, ...)
     */
    private AccessDecision handleReadOperation(String path, JsonObject operationData, String operationId) {
        ResolvedTarget target = resolveTarget(path, ResolveMode.FOLLOW);
        AccessDecision readDecision = target.getDecision();
        try {
            if (!readDecision.isAllowed()) {
                sendFileOperationResult(operationId, "read", path, false, readDecision.getMessage(), null);
                return readDecision;
            }

            File file = target.getOperand();
            if (!file.exists()) {
                sendFileOperationResult(operationId, "read", path, false, "File not found", null);
                return readDecision;
            }

            if (!file.isFile()) {
                sendFileOperationResult(operationId, "read", path, false, "Path is not a file", null);
                return readDecision;
            }

            int limit = operationData.has("limit") && !operationData.get("limit").isJsonNull()
                ? operationData.get("limit").getAsInt() : 0;
            if (limit <= 0) limit = 1000; // Default limit of 1000 lines

            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null && lineCount < limit) {
                    content.append(line).append("\n");
                    lineCount++;
                }
            }

            JsonObject resultData = new JsonObject();
            resultData.addProperty("content", content.toString().trim());
            resultData.addProperty("size", file.length());
            resultData.addProperty("lastModified", file.lastModified());
            resultData.addProperty("linesRead", content.toString().split("\n").length);

            sendFileOperationResult(operationId, "read", path, true, "File read successfully", resultData);
            return readDecision;

        } catch (Exception e) {
            sendFileOperationResult(operationId, "read", path, false,
                "Error reading file: " + e.getMessage(), null);
            return readDecision;
        }
    }

    /**
     * Handles a file write operation.
     *
     * @return the {@link AccessDecision} that gated this write
     */
    private AccessDecision handleWriteOperation(String path, JsonObject operationData, String operationId) {
        ResolvedTarget target = resolveTarget(path, ResolveMode.FOLLOW);
        AccessDecision writeDecision = target.getDecision();
        try {
            if (!writeDecision.isAllowed()) {
                sendFileOperationResult(operationId, "write", path, false, writeDecision.getMessage(), null);
                return writeDecision;
            }

            File file = target.getOperand();
            String content = operationData.has("content") && !operationData.get("content").isJsonNull()
                ? operationData.get("content").getAsString() : null;
            boolean append = operationData.has("append") && !operationData.get("append").isJsonNull()
                && operationData.get("append").getAsBoolean();

            if (content == null) {
                sendFileOperationResult(operationId, "write", path, false, "Content cannot be null", null);
                return writeDecision;
            }

            // Ensure the parent directory exists. The operand is the real existing prefix plus
            // ordinary missing names only, so this never creates anything outside the root.
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            try (FileWriter writer = new FileWriter(file, append)) {
                writer.write(content);
            }

            JsonObject resultData = new JsonObject();
            resultData.addProperty("bytesWritten", content.getBytes().length);
            resultData.addProperty("fileSize", file.length());

            sendFileOperationResult(operationId, "write", path, true, "File written successfully", resultData);
            return writeDecision;

        } catch (Exception e) {
            sendFileOperationResult(operationId, "write", path, false,
                "Error writing file: " + e.getMessage(), null);
            return writeDecision;
        }
    }
    
    /**
     * Handles a directory listing operation.
     *
     * @return the {@link AccessDecision} that gated listing the directory itself — one decision
     *         for the whole operation, not one per returned row (see {@link #recordFileDecision})
     */
    // PMD.UnusedFormalParameter: operationData is deliberately kept even though this handler
    // does not read it today. handleFileOperation()'s switch dispatches all four operation
    // handlers — handleReadOperation, handleWriteOperation, handleListOperation,
    // handleDeleteOperation — with the identical (String, JsonObject, String) signature; the
    // other three do read operationData (limit, content/append, recursive flag). Keeping the
    // family uniform is the deliberate choice, not an oversight.
    @SuppressWarnings("PMD.UnusedFormalParameter")
    private AccessDecision handleListOperation(String path, JsonObject operationData, String operationId) {
        ResolvedTarget target = resolveTarget(path, ResolveMode.FOLLOW);
        AccessDecision listDecision = target.getDecision();
        try {
            if (!listDecision.isAllowed()) {
                sendFileOperationResult(operationId, "list", path, false, listDecision.getMessage(), null);
                return listDecision;
            }

            File dir = target.getOperand();
            if (!dir.exists()) {
                sendFileOperationResult(operationId, "list", path, false, "Directory not found", null);
                return listDecision;
            }

            if (!dir.isDirectory()) {
                sendFileOperationResult(operationId, "list", path, false, "Path is not a directory", null);
                return listDecision;
            }

            File[] files = dir.listFiles();
            if (files == null) {
                sendFileOperationResult(operationId, "list", path, false, "Cannot read directory", null);
                return listDecision;
            }

            JsonArray fileList = new JsonArray();
            for (File file : files) {
                fileList.add(buildListEntry(path, file));
            }

            JsonObject resultData = new JsonObject();
            resultData.add("files", fileList);
            resultData.addProperty("totalCount", fileList.size());

            sendFileOperationResult(operationId, "list", path, true, "Directory listed successfully", resultData);
            return listDecision;

        } catch (Exception e) {
            sendFileOperationResult(operationId, "list", path, false,
                "Error listing directory: " + e.getMessage(), null);
            return listDecision;
        }
    }

    /**
     * Builds a single directory-listing row, applying D-18's mark-not-filter policy to a refused
     * child entry. Extracted from {@link #handleListOperation(String, JsonObject, String)} to keep
     * that method's cyclomatic/NPath complexity within the project's PMD threshold — this is the
     * per-entry decision-and-marking step, the cohesive seam in that loop.
     * <p>
     * A file child is marked refused exactly when a read of that child would be refused: its
     * decision comes from {@link #resolveTarget(String, ResolveMode)} in follow mode, so an
     * in-root alias whose real target lies outside every editable root is marked too, not only a
     * child whose name the lexical layer rejects. Directory children keep the never-refused rule.
     *
     * @param basePath the directory path being listed (the parent of {@code file})
     * @param file     one child entry returned by {@code dir.listFiles()}
     * @return the JSON row for this entry, either full metadata or a withheld-metadata marker
     */
    private JsonObject buildListEntry(String basePath, File file) {
        // D-18: a refused child is MARKED, not filtered out — the panel used to render
        // "this file does not exist" for a file that exists and is protected.
        String childPath = (basePath == null || basePath.isEmpty() || basePath.equals("/"))
            ? file.getName()
            : basePath + "/" + file.getName();
        AccessDecision childDecision = file.isDirectory()
            ? AccessDecision.allowed()
            : resolveTarget(childPath, ResolveMode.FOLLOW).getDecision();
        boolean refused = !childDecision.isAllowed();

        JsonObject fileInfo = new JsonObject();
        fileInfo.addProperty("name", file.getName());
        fileInfo.addProperty("isDirectory", file.isDirectory());
        if (refused) {
            // Deliberately withholds size/lastModified/readable/writable — marking an
            // entry must not leak the metadata the refusal exists to withhold (T-06-27).
            fileInfo.addProperty("accessible", false);
            fileInfo.addProperty("reason", reasonCodeFor(childDecision));
        } else {
            fileInfo.addProperty("accessible", true);
            fileInfo.addProperty("size", file.isDirectory() ? 0 : file.length());
            fileInfo.addProperty("lastModified", file.lastModified());
            fileInfo.addProperty("readable", file.canRead());
            fileInfo.addProperty("writable", file.canWrite());
        }
        return fileInfo;
    }

    /**
     * Handles a file delete operation.
     * <p>
     * The target is resolved in entry mode ({@link ResolveMode#ENTRY}): the parent is followed,
     * the terminal entry itself is not. A terminal symbolic link — to a file, to a directory, or
     * dangling — is removed as a link and its target is never touched (JDK
     * {@link Files#delete(Path)} semantics; for a link to a directory this is the maintainer's
     * recorded link-only decision). A real directory keeps the recursive-flag and
     * protected-descendant gates in front of {@link #deleteDirectory}; a regular file is
     * deleted as before.
     *
     * @return the {@link AccessDecision} that gated this delete
     */
    private AccessDecision handleDeleteOperation(String path, JsonObject operationData, String operationId) {
        ResolvedTarget target = resolveTarget(path, ResolveMode.ENTRY);
        AccessDecision deleteDecision = target.getDecision();
        try {
            if (!deleteDecision.isAllowed()) {
                sendFileOperationResult(operationId, "delete", path, false, deleteDecision.getMessage(), null);
                return deleteDecision;
            }

            File entry = target.getOperand();
            if (!Files.exists(entry.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                sendFileOperationResult(operationId, "delete", path, false, "File not found", null);
                return deleteDecision;
            }

            AccessDecision directoryDecision = directoryDeleteGate(entry, operationData, target.getMappedPath());
            if (!directoryDecision.isAllowed()) {
                sendFileOperationResult(operationId, "delete", path, false,
                    directoryDecision.getMessage(), null);
                return directoryDecision;
            }

            boolean deleted = deleteEntry(entry);

            if (deleted) {
                sendFileOperationResult(operationId, "delete", path, true, "File deleted successfully", null);
            } else {
                sendFileOperationResult(operationId, "delete", path, false, "Failed to delete file", null);
            }
            return deleteDecision;

        } catch (Exception e) {
            sendFileOperationResult(operationId, "delete", path, false,
                "Error deleting file: " + e.getMessage(), null);
            return deleteDecision;
        }
    }

    /**
     * The gates a <b>real</b> directory entry passes before {@link #deleteDirectory} may run:
     * D-20's recursive-flag shape guard first, then the protected-descendant walk. A terminal
     * symbolic link (even one pointing to a directory) and a regular file pass through — a link
     * delete removes only the link, so there is no subtree to guard.
     * <p>
     * The ancestor walk closes the gap a phase-06 security audit found: the path decision only
     * ever ran against the requested entry itself, never against what is underneath it, so a
     * recursive delete of an ancestor of a protected path (e.g. {@code plugins/UltiTools}, an
     * ancestor of {@code plugins/UltiTools/security} and of {@code plugins/UltiTools/data.json})
     * reached {@link #deleteDirectory} with no policy consulted for any descendant at all.
     *
     * @param entry         the resolved, not-followed delete operand
     * @param operationData the inbound request's data object (carries {@code recursive})
     * @param mappedPath    the entry's server-root-relative path, as mapped by the resolver
     * @return {@link AccessDecision#allowed()}, or the first gate's non-configurable refusal
     */
    private AccessDecision directoryDeleteGate(File entry, JsonObject operationData, String mappedPath) {
        if (Files.isSymbolicLink(entry.toPath()) || !entry.isDirectory()) {
            return AccessDecision.allowed();
        }
        AccessDecision recursiveDecision = requireRecursiveFlag(operationData);
        if (!recursiveDecision.isAllowed()) {
            return recursiveDecision;
        }
        return deniedAsAncestorOfProtectedPath(mappedPath, entry);
    }

    /**
     * Removes one resolved delete entry. A symbolic link is removed as a link with
     * {@link Files#delete(Path)}, which never follows it (D-02); a real directory is emptied by
     * {@link #deleteDirectory}; anything else is deleted as a single file.
     *
     * @param entry the resolved, not-followed delete operand
     * @return whether the entry was removed
     * @throws IOException if removing a link fails
     */
    private boolean deleteEntry(File entry) throws IOException {
        Path entryPath = entry.toPath();
        if (Files.isSymbolicLink(entryPath)) {
            Files.delete(entryPath);
            return true;
        }
        return entry.isDirectory() ? deleteDirectory(entry) : entry.delete();
    }

    /**
     * D-20's shape guard: deleting a directory requires the request's data to carry a
     * {@code recursive} member that is a JSON boolean primitive with value {@code true}.
     * <p>
     * A present-but-non-boolean {@code recursive} (e.g. the string {@code "false"}) is treated as
     * absent rather than coerced — Gson's {@code JsonPrimitive.getAsBoolean()} is lenient on a
     * string primitive, and a request carrying the string {@code "false"} must not delete a world
     * tree. The missing field is never defaulted to {@code true} for backward compatibility: D-20
     * accepts the break because {@code file-delete} defaults off, so a caller already reaching
     * this code had the capability turned on deliberately, and fails here with a clear reason
     * rather than silently.
     *
     * @param operationData the inbound request's data object
     * @return {@link AccessDecision#allowed()} when {@code recursive} is present and {@code true};
     *         otherwise a denied, non-configurable decision naming the missing/invalid field — no
     *         configuration makes an unflagged recursive delete valid
     */
    private static AccessDecision requireRecursiveFlag(JsonObject operationData) {
        com.google.gson.JsonElement recursive = operationData.get("recursive");
        boolean explicitlyTrue = recursive != null
                && recursive.isJsonPrimitive()
                && recursive.getAsJsonPrimitive().isBoolean()
                && recursive.getAsBoolean();
        if (explicitlyTrue) {
            return AccessDecision.allowed();
        }
        return AccessDecision.deniedNonConfigurable(
                "deleting a directory requires the request's 'recursive' field to be the boolean true");
    }

    /**
     * Refuses a recursive directory delete whose target is an ancestor of any path
     * {@link #deniedUnconditionally} would refuse on its own. Runs after {@link
     * #requireRecursiveFlag} and strictly before {@link #deleteDirectory}; nothing is deleted
     * when this refuses. {@link #deleteDirectory} itself is unchanged — the walk it performs
     * stays a naked recursive delete, and this method is the gate that decides whether it may
     * run at all, not a per-descendant check woven into it.
     * <p>
     * <b>Why this walks the real directory instead of comparing paths lexically.</b> One of
     * {@link #deniedUnconditionally}'s rules — the security-directory check — is answerable from
     * the path alone: {@link #SECURITY_DIR_RELATIVE} is a single fixed location, so "is {@code
     * dir} an ancestor of it" is just {@link Path#startsWith(Path)} run in the opposite
     * direction from the existing direct check. But the credential rules ({@link
     * #DENY_EXACT_BASENAMES}, the glob patterns, {@link #BLOCKED_FILES}, {@link
     * #BLOCKED_EXTENSIONS}) match on a <b>basename</b>, wherever it occurs in the tree — "is
     * {@code dir} an ancestor of some path a glob would deny" cannot be answered from {@code
     * dir}'s own path at all; it depends on what files actually exist underneath it. A recursive
     * delete already requires the real directory handle, and this gate runs before any deletion,
     * so walking the real subtree is exactly as knowable at gate time as the lexical check would
     * be for the security-directory rule alone — and it is the one approach that covers every
     * rule {@link #deniedUnconditionally} enforces with a single method, rather than hand-listing
     * the two paths this defect happened to be filed against. A credential pattern or protected
     * directory added later is covered automatically; nobody has to remember to extend a second
     * list.
     * <p>
     * <b>Which path it walks.</b> {@code normalizedPath} is the resolver's mapped path for the
     * real directory entry — its server-root-relative location through the configured root it
     * belongs to — not the raw request string, so a request that reaches a directory through an
     * in-root alias of its parent is checked against where the directory really is.
     * <p>
     * <b>What this does not cover.</b> A symlink <em>inside</em> the tree whose target resolves
     * to a protected location the link's own relative path would not itself trigger —
     * {@code File#listFiles()} enumerates the directory's own entries, and {@link
     * #deleteDirectory} follows a descendant link to a directory. That descendant-link
     * limitation is documented and deferred; this walk does not attempt to close it.
     *
     * @param normalizedPath the mapped server-root-relative path of {@code dir} (forward-slash
     *                        separated, no leading slash), used to build each descendant's path
     * @param dir             the real directory {@link #handleDeleteOperation} is about to
     *                        recursively delete
     * @return {@link AccessDecision#allowed()} if no descendant (at any depth) is unconditionally
     *         denied; otherwise a denied, non-configurable decision naming the first protected
     *         descendant found — non-configurable because every rule {@link
     *         #deniedUnconditionally} enforces is itself non-configurable
     */
    private AccessDecision deniedAsAncestorOfProtectedPath(String normalizedPath, File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return AccessDecision.allowed();
        }
        for (File child : children) {
            String childPath = normalizedPath.isEmpty()
                    ? child.getName()
                    : normalizedPath + "/" + child.getName();
            AccessDecision childDenial = deniedUnconditionally(childPath);
            if (childDenial != null) {
                return AccessDecision.deniedNonConfigurable(
                        "recursive delete of '" + normalizedPath + "' would remove '" + childPath
                                + "', which is unconditionally protected");
            }
            if (child.isDirectory()) {
                AccessDecision nested = deniedAsAncestorOfProtectedPath(childPath, child);
                if (!nested.isAllowed()) {
                    return nested;
                }
            }
        }
        return AccessDecision.allowed();
    }

    /**
     * Recursively deletes a directory.
     */
    private boolean deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        return dir.delete();
    }
    
    /**
     * The one resolver every remote file operation takes its decision and its I/O operand from
     * (D-21, issue #672). The path that is authorized is the path that is used: no handler builds
     * a {@link File} from the raw request string.
     * <p>
     * Steps, each of which can produce the final decision:
     * <ol>
     *   <li><b>Lexical layer</b> — {@link #isPathAllowed(String)} exactly as before (unconditional
     *       deny layer first, then the lexical editable-root check).</li>
     *   <li><b>Resolution without lexical collapse</b> — the request's components are walked from
     *       the real server root; every existing prefix is resolved by the filesystem
     *       ({@link Path#toRealPath}), so a {@code ..} after a symbolic link means what the
     *       operating system means, not what {@link Path#normalize()} would guess. At the first
     *       component that does not exist, following stops: that component must not exist even
     *       as a link, and it and every later component must be an ordinary name (not {@code .}
     *       or {@code ..}). A dangling or looping link, a component whose existence cannot be
     *       determined, or a resolution failure is an unsafe shape and is refused
     *       non-configurably — an operator can widen the roots but cannot make an ambiguous path
     *       safe.</li>
     *   <li><b>Real containment</b> — the operand must lie inside the real path of a configured
     *       editable root, compared by {@link Path#startsWith(Path)} name element (a sibling
     *       {@code <root>-evil} is not inside {@code <root>}); otherwise the refusal is
     *       configurable through {@code ultipanel.files.editable-roots}. A configured root that
     *       does not resolve is skipped with a warning (T-06-23).</li>
     *   <li><b>Actual-target deny</b> — {@link #deniedUnconditionally(String)} runs again on the
     *       operand's mapped path, so a credential file or the action-log directory reached
     *       through an in-root alias (a link, or a linked parent directory) is refused with the
     *       same non-configurable decision a direct request for it gets (D-08).</li>
     * </ol>
     * In {@link ResolveMode#FOLLOW} the operand is the resolved real object, or the real existing
     * prefix plus the ordinary missing names of a new write target. In {@link ResolveMode#ENTRY}
     * (delete) the parent is resolved as in follow mode, the terminal name must be an ordinary
     * name, and the operand is the parent's real path plus that name, not followed.
     *
     * @param path the requested path, forward- or back-slash separated, with or without a
     *             leading slash
     * @param mode whether the terminal component is followed
     * @return the final decision, and when allowed the operand and its mapped path
     */
    ResolvedTarget resolveTarget(String path, ResolveMode mode) {
        AccessDecision lexical = isPathAllowed(path);
        if (!lexical.isAllowed()) {
            return ResolvedTarget.denied(lexical);
        }
        String normalized = normalize(path);
        Path operand;
        try {
            List<String> names = componentsOf(normalized);
            operand = mode == ResolveMode.ENTRY ? resolveEntry(names) : resolveFollowing(names);
        } catch (UnsafePathException e) {
            return ResolvedTarget.denied(AccessDecision.deniedNonConfigurable(
                    "'" + normalized + "' " + e.getMessage()));
        }
        return containedTarget(normalized, operand);
    }

    /**
     * Splits a normalized request into its name components. java.nio parsing collapses doubled
     * and trailing separators, so {@code root//sub/} and {@code root/sub} name the same object;
     * {@code .} and {@code ..} are kept as components for the walk to resolve.
     */
    private static List<String> componentsOf(String normalized) throws UnsafePathException {
        List<String> names = new ArrayList<>();
        if (normalized.isEmpty()) {
            return names;
        }
        Path parsed;
        try {
            parsed = Paths.get(normalized);
        } catch (InvalidPathException e) {
            throw new UnsafePathException("is not a valid path");
        }
        for (Path name : parsed) {
            names.add(name.toString());
        }
        return names;
    }

    /**
     * Follow-mode walk (step 2): every existing component is resolved by the filesystem; the
     * first missing component starts the missing suffix. Below a real non-directory nothing can
     * exist, so the rest is treated as missing too and a read or list of it fails ordinarily.
     */
    private Path resolveFollowing(List<String> names) throws UnsafePathException {
        Path current = realPathOf(serverRoot.toPath());
        for (int i = 0; i < names.size(); i++) {
            Path next = current.resolve(names.get(i));
            if (!Files.isDirectory(current) || Files.notExists(next, LinkOption.NOFOLLOW_LINKS)) {
                return appendMissingSuffix(current, names.subList(i, names.size()));
            }
            if (!Files.exists(next)) {
                throw new UnsafePathException("contains a dangling or looping symbolic link, or a component "
                        + "whose existence cannot be determined");
            }
            current = realPathOf(next);
        }
        return current;
    }

    /**
     * Entry-mode resolution (delete): the parent is followed, the terminal entry is not. The
     * terminal name must be an ordinary name, so {@code sub/..}, {@code .} or an empty path never
     * names some other directory to remove.
     */
    private Path resolveEntry(List<String> names) throws UnsafePathException {
        if (names.isEmpty()) {
            throw new UnsafePathException("names no entry that can be deleted");
        }
        String terminal = names.get(names.size() - 1);
        requireOrdinaryName(terminal);
        return resolveFollowing(names.subList(0, names.size() - 1)).resolve(terminal);
    }

    private static Path appendMissingSuffix(Path existing, List<String> missing) throws UnsafePathException {
        Path result = existing;
        for (String name : missing) {
            requireOrdinaryName(name);
            result = result.resolve(name);
        }
        return result;
    }

    private static void requireOrdinaryName(String name) throws UnsafePathException {
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            throw new UnsafePathException("uses '" + name + "' where only an ordinary file or directory name "
                    + "can be located safely");
        }
    }

    private static Path realPathOf(Path path) throws UnsafePathException {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new UnsafePathException("could not be resolved");
        }
    }

    /**
     * Step 3: real containment. Returns the target with its mapped path (after step 4), or the
     * configurable outside-the-roots refusal.
     */
    private ResolvedTarget containedTarget(String normalized, Path operand) {
        for (File root : editableRoots) {
            Path realRoot = realRootOf(root);
            if (realRoot != null && operand.startsWith(realRoot)) {
                return unlessProtected(operand.toFile(), mappedPath(root, realRoot, operand));
            }
        }
        return ResolvedTarget.denied(AccessDecision.deniedConfigurable(
                "'" + normalized + "' resolves outside the editable roots", EDITABLE_ROOTS_CONFIG_KEY));
    }

    /**
     * Step 4 (D-08): the unconditional deny layer applied to where the operand really is. In
     * entry mode the mapped path names the entry itself, so deleting an alias link is judged on
     * the link, not on what it points to.
     */
    private ResolvedTarget unlessProtected(File operand, String mappedPath) {
        AccessDecision deny = deniedUnconditionally(mappedPath);
        return deny != null ? ResolvedTarget.denied(deny) : ResolvedTarget.allowed(operand, mappedPath);
    }

    /**
     * A configured root's real path, or {@code null} — with a loud warning rather than silently
     * narrowing the set to nothing — when it does not resolve (T-06-23: an operator typo).
     */
    private static Path realRootOf(File root) {
        try {
            return root.toPath().toRealPath();
        } catch (IOException e) {
            logWarning("Configured editable root does not exist and was skipped: " + root.getPath()
                    + " (check " + EDITABLE_ROOTS_CONFIG_KEY + " in plugins/UltiTools/config.yml)");
            return null;
        }
    }

    /**
     * The operand's server-root-relative path expressed through the configured root it belongs
     * to: the root's configured location relative to {@link #serverRoot}, followed by the
     * operand's location inside the root's real path. This is the path the unconditional deny
     * rules and the protected-descendant walk are evaluated against.
     */
    private String mappedPath(File root, Path realRoot, Path operand) {
        Path base = serverRoot.toPath().toAbsolutePath().normalize();
        String prefix = base.relativize(root.toPath().toAbsolutePath().normalize()).toString();
        String inside = realRoot.relativize(operand).toString();
        String joined;
        if (prefix.isEmpty()) {
            joined = inside;
        } else if (inside.isEmpty()) {
            joined = prefix;
        } else {
            joined = prefix + "/" + inside;
        }
        return joined.replace(File.separatorChar, '/');
    }
    
    /**
     * Sends the file operation result.
     */
    private void sendFileOperationResult(String operationId, String operation, String path, 
                                       boolean success, String message, JsonObject data) {
        try {
            JsonObject resultMessage = new JsonObject();
            resultMessage.addProperty("type", "file_operation_result");
            
            JsonObject resultData = new JsonObject();
            resultData.addProperty("operationId", operationId);
            resultData.addProperty("operation", operation);
            resultData.addProperty("path", path);
            resultData.addProperty("success", success);
            resultData.addProperty("message", message);
            resultData.addProperty("timestamp", System.currentTimeMillis());
            
            if (data != null) {
                for (String key : data.keySet()) {
                    resultData.add(key, data.get(key));
                }
            }
            
            resultMessage.add("data", resultData);
            resultMessage.addProperty("serverId", webSocketClient.getServerId());
            
            webSocketClient.sendMessage(resultMessage);
            
            PanelConnectionLog.log(Level.INFO, 
                FrameworkText.format("文件操作结果已发送 (ID: %s, 操作: %s, 成功: %s)", 
                operationId, operation, success));
            
        } catch (Exception e) {
            PanelConnectionLog.log(Level.WARNING, FrameworkText.format("发送文件操作结果失败: %s", e.getMessage()));
        }
    }

    /**
     * How {@link #resolveTarget(String, ResolveMode)} treats the request's terminal component.
     */
    enum ResolveMode {
        /** Read, write, list and list-child marking: every existing component is followed. */
        FOLLOW,
        /** Delete: the parent is followed; the terminal entry itself is never followed (D-02). */
        ENTRY
    }

    /**
     * The result of {@link #resolveTarget(String, ResolveMode)}: the final decision and, when it
     * is allowed, the operand the handler performs I/O on and that operand's mapped
     * server-root-relative path.
     */
    static final class ResolvedTarget {
        private final AccessDecision decision;
        private final File operand;
        private final String mappedPath;

        private ResolvedTarget(AccessDecision decision, File operand, String mappedPath) {
            this.decision = decision;
            this.operand = operand;
            this.mappedPath = mappedPath;
        }

        static ResolvedTarget denied(AccessDecision decision) {
            return new ResolvedTarget(decision, null, null);
        }

        static ResolvedTarget allowed(File operand, String mappedPath) {
            return new ResolvedTarget(AccessDecision.allowed(), operand, mappedPath);
        }

        /** @return the final decision for the request */
        AccessDecision getDecision() {
            return decision;
        }

        /** @return the I/O operand, or {@code null} when the decision is a refusal */
        File getOperand() {
            return operand;
        }

        /** @return the operand's mapped server-root-relative path, or {@code null} when refused */
        String getMappedPath() {
            return mappedPath;
        }
    }

    /** A request shape that cannot be located safely; always becomes a non-configurable refusal. */
    private static final class UnsafePathException extends Exception {
        private static final long serialVersionUID = 1L;

        UnsafePathException(String message) {
            super(message);
        }
    }
}
