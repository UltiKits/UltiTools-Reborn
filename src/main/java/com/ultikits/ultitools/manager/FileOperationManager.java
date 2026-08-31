package com.ultikits.ultitools.manager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
import com.ultikits.ultitools.utils.PluginInitiationUtils;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;
import org.jetbrains.annotations.ApiStatus;

/**
 * 文件操作管理器
 * 负责处理来自WebSocket的文件操作请求
 *
 * <p>The remote file API is confined to two layers (D-14): an unconditional, non-configurable
 * deny layer for credential-bearing files and the framework's own action-log directory, and an
 * operator-configured editable-root set (D-15) that answers "where" once the deny layer has
 * already answered "not this, ever". See {@link #isPathAllowed(String)} for the ordering
 * rationale.
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

    /** D-16's exact-basename credential set — matched case-insensitively, see {@link #basenameOf}. */
    private static final Set<String> DENY_EXACT_BASENAMES = new HashSet<String>(Arrays.asList(
        "data.json", "secring.gpg", "access_key.txt", ".dev.vars"
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
     * 设置WebSocket客户端
     * @param client WebSocket客户端
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
     * <b>{@code plugins/UltiTools/data.json}</b> holds the live UltiCloud access and refresh
     * tokens ({@code CloudAuthManager}), which is why D-19 names it explicitly rather than leaving
     * it to the glob patterns. It is matched by exact basename below, not merely by the
     * literal {@code plugins/UltiTools} prefix, so a credential file with the same name anywhere
     * else is caught too.
     * <p>
     * <b>The {@code plugins/UltiTools/security/} rule</b> is what lets the remote action log
     * ({@code RemoteActionLog}) live inside {@code getDataFolder()} — Bukkit convention intact —
     * instead of being physically relocated (D-23/D-31). Accepted cost: the log and the
     * credential files now share one trust anchor; a defect in this layer loses both.
     * <p>
     * Folds in the pre-6.3.0 {@link #BLOCKED_FILES}/{@link #BLOCKED_EXTENSIONS} sets so every
     * unconditional refusal produces the same non-configurable decision shape.
     * <p>
     * 本层是不可配置的拒绝层（D-16），不读取任何配置键，也不查询任何 {@code Capability}——这里的
     * 任何一条规则都不能被配置打开。授权面板管理文件，不等于授权它拿走面板自己的钥匙（D-01）：
     * 被窃取的刷新令牌是持久性访问权限，操作员无法通过改密码撤销；而一次误配置的可编辑根目录，
     * 一次配置修改就能立即关闭。{@code plugins/UltiTools/data.json} 保存着 UltiCloud
     * 的实时访问与刷新令牌，D-19 因此显式点名了它，而不是交给通配模式兜底。{@code plugins/UltiTools/security/}
     * 规则使远程动作日志得以留在 {@code getDataFolder()} 内、符合 Bukkit 约定，而不必物理迁移；
     * 代价是日志与凭据文件现在共享同一个信任锚点。
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
     * call before any real file exists. {@link #getSecureFile(String)} performs the real,
     * symlink-aware containment check once a file handle is actually needed.
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
     * 处理文件操作请求
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
                String.format("收到文件操作请求: %s, 路径: %s (ID: %s)", operation, path, operationId));

            // Captured before the async hop — see this method's javadoc.
            String capturedOperation = operation;
            String capturedPath = path;
            String capturedOperationId = operationId;
            String capturedActor = actor;

            // 异步处理文件操作
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
            UltiTools.getInstance().getLogger().log(Level.WARNING, "文件操作处理失败: " + e.getMessage());
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
     * 处理文件读取操作
     *
     * @return the {@link AccessDecision} that gated this read — the value
     *         {@link #recordFileDecision} records, regardless of whether the read then succeeded
     *         or failed for an unrelated reason (file not found, not a regular file, ...)
     */
    private AccessDecision handleReadOperation(String path, JsonObject operationData, String operationId) {
        AccessDecision readDecision = isPathAllowed(path);
        try {
            if (!readDecision.isAllowed()) {
                sendFileOperationResult(operationId, "read", path, false, readDecision.getMessage(), null);
                return readDecision;
            }

            File file = getSecureFile(path);
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
            if (limit <= 0) limit = 1000; // 默认限制1000行

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
     * 处理文件写入操作
     *
     * @return the {@link AccessDecision} that gated this write
     */
    private AccessDecision handleWriteOperation(String path, JsonObject operationData, String operationId) {
        AccessDecision writeDecision = isPathAllowed(path);
        try {
            if (!writeDecision.isAllowed()) {
                sendFileOperationResult(operationId, "write", path, false, writeDecision.getMessage(), null);
                return writeDecision;
            }

            File file = getSecureFile(path);
            String content = operationData.has("content") && !operationData.get("content").isJsonNull()
                ? operationData.get("content").getAsString() : null;
            boolean append = operationData.has("append") && !operationData.get("append").isJsonNull()
                && operationData.get("append").getAsBoolean();

            if (content == null) {
                sendFileOperationResult(operationId, "write", path, false, "Content cannot be null", null);
                return writeDecision;
            }

            // 确保父目录存在
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
     * 处理目录列表操作
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
        AccessDecision listDecision = isPathAllowed(path);
        try {
            if (!listDecision.isAllowed()) {
                sendFileOperationResult(operationId, "list", path, false, listDecision.getMessage(), null);
                return listDecision;
            }

            File dir = getSecureFile(path);
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
        AccessDecision childDecision = isPathAllowed(childPath);
        boolean refused = !file.isDirectory() && !childDecision.isAllowed();

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
     * 处理文件删除操作
     *
     * @return the {@link AccessDecision} that gated this delete
     */
    private AccessDecision handleDeleteOperation(String path, JsonObject operationData, String operationId) {
        AccessDecision deleteDecision = isPathAllowed(path);
        try {
            if (!deleteDecision.isAllowed()) {
                sendFileOperationResult(operationId, "delete", path, false, deleteDecision.getMessage(), null);
                return deleteDecision;
            }

            File file = getSecureFile(path);
            if (!file.exists()) {
                sendFileOperationResult(operationId, "delete", path, false, "File not found", null);
                return deleteDecision;
            }

            if (file.isDirectory()) {
                // D-20: a recursive delete requires the caller to say so — the shape guard, not
                // the walk. deleteDirectory() itself stays unchanged; this gate runs in front of
                // it, after the path decision and the existence check, before any deletion.
                AccessDecision recursiveDecision = requireRecursiveFlag(operationData);
                if (!recursiveDecision.isAllowed()) {
                    sendFileOperationResult(operationId, "delete", path, false,
                        recursiveDecision.getMessage(), null);
                    return recursiveDecision;
                }

                // Closes the ancestor gap a phase-06 security audit found: the path decision
                // above (isPathAllowed -> deniedUnconditionally) only ever ran against the
                // requested path itself, never against what is underneath it. A recursive delete
                // of an ancestor of a protected path (e.g. plugins/UltiTools, an ancestor of
                // plugins/UltiTools/security and of plugins/UltiTools/data.json) reached
                // deleteDirectory() with no policy consulted for any descendant at all. Runs
                // after the recursive-flag shape guard and strictly before deleteDirectory() is
                // called — nothing is removed if this refuses.
                AccessDecision ancestorDecision = deniedAsAncestorOfProtectedPath(normalize(path), file);
                if (!ancestorDecision.isAllowed()) {
                    sendFileOperationResult(operationId, "delete", path, false,
                        ancestorDecision.getMessage(), null);
                    return ancestorDecision;
                }
            }

            boolean deleted = false;
            if (file.isDirectory()) {
                // 递归删除目录（谨慎操作）
                deleted = deleteDirectory(file);
            } else {
                deleted = file.delete();
            }

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
     * <b>What this does not cover.</b> A symlink inside the tree whose target resolves to a
     * protected location the link's own relative path would not itself trigger — {@code
     * File#listFiles()} enumerates the directory's own entries, and following such a link across
     * a policy boundary is the same lexical-vs-real-path asymmetry already tracked and
     * deliberately not fixed this phase; this walk does not attempt to close it.
     *
     * @param normalizedPath the normalized path of {@code dir} (forward-slash separated, no
     *                        leading slash), used to build each descendant's relative path
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
     * 递归删除目录
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
     * 获取安全的文件路径（防止路径遍历攻击）
     */
    /**
     * Resolves a requested path to a real {@link File} handle, refusing anything that does not
     * canonically resolve inside one of the configured {@link #editableRoots} (D-21).
     * <p>
     * The pre-6.3.0 defect here was never the canonicalization order — {@code getCanonicalPath()}
     * already ran before the check, and already resolved symlinks. The live bug was
     * {@code String.startsWith} with no separator boundary: {@code "/srv/mc-evil/x"} literally
     * starts with the characters {@code "/srv/mc"}. This rewrite compares by {@link Path} name
     * element via {@link Path#startsWith(Path)} instead, mirroring the Zip-Slip guard already
     * correct for the opposite direction in {@code UltiToolsPlugin.saveResources()} — it appends
     * {@code File.separator} to its own canonical base before comparing — the same defect class,
     * already fixed once in this repository.
     * <p>
     * A requested path that does not exist yet (a create/write target) resolves its nearest
     * <em>existing</em> ancestor via {@link #resolveNearestReal(Path)} rather than throwing, so a
     * legitimate create into an existing directory is not refused as a traversal attempt — but an
     * ancestor that itself resolves outside every root is still refused (T-06-21).
     *
     * @param path the requested path, forward- or back-slash separated, with or without a
     *             leading slash
     * @return a {@link File} handle for the (not-yet-canonicalized) requested location — callers
     *         operate on this handle, not on the resolved candidate used only for the check
     * @throws SecurityException if the path is empty, unresolvable, or resolves outside every
     *                            configured editable root
     */
    private File getSecureFile(String path) throws SecurityException {
        if (path == null || path.trim().isEmpty()) {
            throw new SecurityException("Path cannot be empty");
        }

        String normalized = normalize(path);
        File requestedFile = new File(serverRoot, normalized);
        // Lexically collapse ".." before any filesystem access — the security-relevant
        // containment check below still requires a REAL (symlink-resolved) path, but a
        // traversal segment must not survive into the not-yet-existing-ancestor walk either.
        Path requestedPath = requestedFile.toPath().normalize();

        Path resolvedCandidate;
        try {
            resolvedCandidate = resolveNearestReal(requestedPath);
        } catch (IOException e) {
            throw new SecurityException("Unable to resolve path: " + path);
        }

        if (!isContainedInAnyRealRoot(resolvedCandidate)) {
            throw new SecurityException("Path is outside the editable roots: " + path);
        }

        return requestedFile;
    }

    /**
     * Resolves {@code candidatePath} to its real (symlink-resolved) form. If the path itself does
     * not exist yet, walks up to the nearest existing ancestor, resolves that ancestor for real,
     * and re-appends the unresolved trailing segments — Claude's Discretion hazard #1 in
     * 06-RESEARCH.md: a not-yet-existing create/write target must not be refused outright.
     *
     * @throws IOException if no ancestor of {@code candidatePath} exists (should not happen once
     *                      {@code serverRoot} itself exists) or the real path cannot be resolved
     */
    private static Path resolveNearestReal(Path candidatePath) throws IOException {
        if (Files.exists(candidatePath)) {
            return candidatePath.toRealPath();
        }
        Path parent = candidatePath.getParent();
        if (parent == null) {
            throw new NoSuchFileException(candidatePath.toString());
        }
        Path realParent = resolveNearestReal(parent);
        Path fileName = candidatePath.getFileName();
        return fileName == null ? realParent : realParent.resolve(fileName).normalize();
    }

    /**
     * Checks {@code resolvedCandidate} against every configured editable root's real path.
     * <p>
     * A configured root that does not itself resolve (T-06-23: an operator typo) is skipped with
     * a loud warning rather than silently narrowing the set to nothing — the remaining configured
     * roots still apply.
     */
    private boolean isContainedInAnyRealRoot(Path resolvedCandidate) {
        for (File root : editableRoots) {
            Path realRoot;
            try {
                realRoot = root.toPath().toRealPath();
            } catch (IOException e) {
                logWarning("Configured editable root does not exist and was skipped: " + root.getPath()
                        + " (check " + EDITABLE_ROOTS_CONFIG_KEY + " in plugins/UltiTools/config.yml)");
                continue;
            }
            if (resolvedCandidate.startsWith(realRoot)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 发送文件操作结果
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
            
            UltiTools.getInstance().getLogger().log(Level.INFO, 
                String.format("文件操作结果已发送 (ID: %s, 操作: %s, 成功: %s)", 
                operationId, operation, success));
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "发送文件操作结果失败: " + e.getMessage());
        }
    }
}
