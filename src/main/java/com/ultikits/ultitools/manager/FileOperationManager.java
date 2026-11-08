package com.ultikits.ultitools.manager;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.AccessDecision;
import com.ultikits.ultitools.utils.CredentialStore;
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
 *
 * <p><b>Protected locations are judged by real
 * path</b> ({@link #deniedAtRealPath(Path, List)}), never by the name of the editable root a
 * request happened to match, so neither an alias root nor the order of the roots moves a
 * protected object out from under the deny layer.
 */
@ApiStatus.Internal
public class FileOperationManager {
    private final File serverRoot;

    /**
     * Protected server-core files, matched by exact basename. Kept equal to the panel's protected
     * list ({@code PROTECTED_SERVER_FILES.names} in UltiCloud's
     * {@code packages/contracts/src/rest/server-files.ts}): the panel refuses these first, and this
     * set refuses them again as defence in depth. Add an entry on both sides together.
     */
    private static final Set<String> BLOCKED_FILES = new HashSet<String>(Arrays.asList(
        "server.properties", "ops.json", "whitelist.json",
        "banned-ips.json", "banned-players.json", "eula.txt",
        "usercache.json", "bukkit.yml", "spigot.yml",
        "paper.yml", "paper-global.yml", "paper-world-defaults.yml",
        "commands.yml", "permissions.yml", "paper-world.yml",
        "purpur.yml", "help.yml"
    ));

    /**
     * Protected extensions, kept equal to the panel's {@code PROTECTED_SERVER_FILES.extensions}
     * (see {@link #BLOCKED_FILES}).
     */
    private static final Set<String> BLOCKED_EXTENSIONS = new HashSet<String>(Arrays.asList(
        ".jar", ".sh", ".bat", ".cmd", ".ps1", ".exe", ".class", ".command"
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
     * The relative location of the framework's own state directory, {@code <server root>/.ultikits}
     * (plan 22-07, T-22-28). It holds {@code credentials.json} -- the linked server's UltiCloud
     * credential -- and {@code cloud-outbox.json}, the reliable-delivery state file, together with
     * their write-time siblings ({@code cloud-outbox.json.<ULID>.tmp},
     * {@code cloud-outbox.json.unreadable}).
     * <p>
     * A {@link #DENY_EXACT_BASENAMES} entry alone would not be enough: the credential basename is
     * protected anywhere, but the state file's temporary and moved-aside siblings carry other names,
     * and the directory itself would still be listable and deletable. This rule therefore protects
     * the directory, by name element exactly like {@link #SECURITY_DIR_RELATIVE} -- a sibling
     * directory named {@code .ultikits-backup} is not caught by it.
     */
    private static final Path STATE_DIR_RELATIVE = Paths.get(".ultikits");

    /** The refusal for a path inside the remote action log's own directory (D-23/D-31). */
    private static final String ACTION_LOG_DIR_REFUSAL =
            "path is inside the remote action log's own directory";

    /** The refusal for a path inside the framework's own state directory (T-22-28). */
    private static final String STATE_DIR_REFUSAL = "path is inside the framework's own state directory "
            + "(.ultikits), which holds the UltiCloud credential and the reliable-delivery state file";

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
        AccessDecision byName = deniedByName(basenameOf(normalizedPath));
        if (byName != null) {
            return byName;
        }
        if (isUnderSecurityDirectory(normalizedPath)) {
            return deniedAsActionLogDirectory();
        }
        if (isUnderStateDirectory(normalizedPath)) {
            return AccessDecision.deniedNonConfigurable(STATE_DIR_REFUSAL);
        }
        return null;
    }

    /**
     * The name rules of the unconditional deny layer: protected server files, protected
     * extensions, credential basenames and credential glob patterns. Shared by the lexical check
     * on the request ({@link #deniedUnconditionally(String)}) and the real-path check on the
     * object a request reaches ({@link #deniedAtRealPath(Path, List)}), so both apply one rule
     * set.
     *
     * @param fileName the entry's own name (the last path element)
     * @return a denied, non-configurable decision, or {@code null} if no name rule matches
     */
    private static AccessDecision deniedByName(String fileName) {
        // Trailing dots and spaces are dropped before matching, as the panel's list does: on
        // Windows "server.properties." opens server.properties, so the suffix must not hide a name.
        String fileNameLower = stripTrailingDotsAndSpaces(fileName).toLowerCase(Locale.ROOT);

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
        return null;
    }

    private static AccessDecision deniedAsActionLogDirectory() {
        return AccessDecision.deniedNonConfigurable(ACTION_LOG_DIR_REFUSAL);
    }

    /**
     * The unconditional deny layer judged on <b>real</b> paths: the name rules of
     * {@link #deniedByName} on the entry's own real name, and containment in a protected
     * location's real path ({@link #protectedRealLocations()}). Nothing here depends on which
     * editable root matched the request or on the order of the configured roots, so an alias
     * root (for example {@code plugins/shadow} linking to {@code plugins/UltiTools}) cannot move a
     * protected object out from under the rule by renaming the path it is reached through.
     *
     * @param realEntry          the real location of the entry: its real path when it is
     *                           followed, or its parent's real path plus its own name when it is
     *                           not (a delete's terminal entry, a child listed from a real
     *                           directory)
     * @param protectedLocations the result of {@link #protectedRealLocations()}
     * @return a denied, non-configurable decision, or {@code null} if no rule refuses the entry
     */
    private static AccessDecision deniedAtRealPath(Path realEntry, List<ProtectedLocation> protectedLocations) {
        Path name = realEntry.getFileName();
        if (name != null) {
            AccessDecision byName = deniedByName(name.toString());
            if (byName != null) {
                return byName;
            }
        }
        for (ProtectedLocation location : protectedLocations) {
            if (realEntry.startsWith(location.location)) {
                return AccessDecision.deniedNonConfigurable(location.refusal);
            }
        }
        return null;
    }

    /**
     * The real locations of the protected directory {@code plugins/UltiTools/security} under
     * {@link #serverRoot}: the location of the directory entry itself (its parent's real path
     * plus {@code security}) and, when that entry exists and resolves elsewhere, its real target
     * too. A part of the path that does not exist yet is appended by name to the deepest existing
     * ancestor's real path, so the rule holds before the action log has been created.
     *
     * @return one or two absolute real paths; empty only if the server root itself cannot be
     *         resolved, in which case no request can be resolved either
     */
    private List<ProtectedLocation> protectedRealLocations() {
        List<ProtectedLocation> locations = new ArrayList<>();
        addProtectedDirectory(locations, SECURITY_DIR_RELATIVE, ACTION_LOG_DIR_REFUSAL);
        addProtectedDirectory(locations, STATE_DIR_RELATIVE, STATE_DIR_REFUSAL);
        return locations;
    }

    /**
     * Adds the real locations of one protected directory (see {@link #protectedRealLocations()}).
     *
     * @param locations   the list to append to
     * @param relative    the directory's location relative to {@link #serverRoot}
     * @param refusal     the reason a path inside it is refused
     */
    private void addProtectedDirectory(List<ProtectedLocation> locations, Path relative, String refusal) {
        Path directory = serverRoot.toPath().toAbsolutePath().normalize().resolve(relative);
        Path parentLocation = realLocationOf(directory.getParent());
        if (parentLocation == null) {
            return;
        }
        Path entryLocation = parentLocation.resolve(directory.getFileName().toString());
        locations.add(new ProtectedLocation(entryLocation, refusal));
        Path target = realPathOrNull(entryLocation);
        if (target != null && !target.equals(entryLocation)) {
            locations.add(new ProtectedLocation(target, refusal));
        }
    }

    /** The real path of {@code path}, or {@code null} if it cannot be determined. */
    private static Path realPathOrNull(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * {@code path}'s real path when it exists; otherwise the real path of its deepest existing
     * ancestor followed by the remaining names. {@code null} only if no ancestor resolves.
     */
    private static Path realLocationOf(Path path) {
        Deque<String> missing = new ArrayDeque<>();
        Path existing = path;
        Path real = realPathOrNull(existing);
        while (real == null && existing != null) {
            Path name = existing.getFileName();
            if (name != null) {
                missing.push(name.toString());
            }
            existing = existing.getParent();
            real = existing == null ? null : realPathOrNull(existing);
        }
        if (real == null) {
            return null;
        }
        for (String name : missing) {
            real = real.resolve(name);
        }
        return real;
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

    /**
     * Lexical containment in {@link #STATE_DIR_RELATIVE}, by name element -- a sibling
     * {@code .ultikits-backup} is not inside it.
     *
     * @param normalizedPath the path already stripped of a leading slash, forward-slash separated
     * @return whether the path names the state directory or something under it
     */
    private static boolean isUnderStateDirectory(String normalizedPath) {
        Path candidate = Paths.get(normalizedPath).normalize();
        return candidate.startsWith(STATE_DIR_RELATIVE);
    }

    private static String stripTrailingDotsAndSpaces(String name) {
        int end = name.length();
        while (end > 0 && (name.charAt(end - 1) == '.' || name.charAt(end - 1) == ' ')) {
            end--;
        }
        return name.substring(0, end);
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
     *   <li><b>Actual-target deny</b> — the unconditional deny layer runs again on the operand's
     *       real path ({@link #deniedAtRealPath(Path, List)}), so a credential file or the
     *       action-log directory reached through an in-root alias (a link, a linked parent
     *       directory, or an editable root that is itself an alias of another root's
     *       subdirectory) is refused with the same non-configurable decision a direct request
     *       for it gets (D-08), whichever root matched and in whatever order the roots are
     *       configured.</li>
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
     * Step 4 (D-08): the unconditional deny layer applied to where the operand really is, by
     * real path ({@link #deniedAtRealPath(Path, List)}), not by the mapped path: the mapped path
     * depends on which root matched first, so an alias root would otherwise rename a protected
     * object out from under the rule. In entry mode the operand is the entry itself (its
     * parent's real path plus its name), so deleting an alias link is judged on the link, not on
     * what it points to.
     */
    private ResolvedTarget unlessProtected(File operand, String mappedPath) {
        AccessDecision deny = deniedAtRealPath(operand.toPath(), protectedRealLocations());
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
     * operand's location inside the root's real path. It depends on which root matched first,
     * so it only names things in replies; no allow or deny decision is taken on it (those use
     * real paths, see {@link #unlessProtected}).
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

    /** One protected directory's real location and the refusal a path inside it produces. */
    private static final class ProtectedLocation {
        private final Path location;
        private final String refusal;

        ProtectedLocation(Path location, String refusal) {
            this.location = location;
            this.refusal = refusal;
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
