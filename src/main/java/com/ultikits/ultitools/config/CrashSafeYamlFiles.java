package com.ultikits.ultitools.config;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ultikits.ultitools.config.document.OperatorFileWriter;
import com.ultikits.ultitools.config.document.OwnedPaths;
import com.ultikits.ultitools.config.document.PlainData;

/**
 * Opt-in complete publication of a module's operator-editable YAML file, on an explicit operator create/edit action.
 * Not for automatic saves, value repair, {@code @ConfigEntity} files, framework state or credentials.
 * <p>
 * <b>Why it cannot overwrite operator content.</b> Existing-file writes own only the named complete key paths and
 * reuse the configuration gate's plain-data, fingerprint, parse/render/reparse and unowned-byte checks. Every other
 * byte (comments, unknown keys, BOM and line endings included) must stay unchanged or the edit is refused. Creation
 * owns the whole file only while no directory entry exists. Neither method updates the caller's live objects.
 * <p>
 * <b>Supported filesystems and metadata.</b> These {@link File}-based methods (snapshots also originate from a
 * {@code File}) use the default filesystem. Publication requires ordinary files on a local default POSIX filesystem
 * with native atomic rename/link semantics, working POSIX attributes and an existing parent directory. An existing
 * final symlink is refused, not followed; parent links are resolved with filesystem semantics. Paths must be trusted
 * module paths, not unsanitized user input; this is not a path sandbox.
 * Existing files keep the nine POSIX permission bits, owner and group, applied and verified before commit; failure
 * aborts publication. Read-only targets are refused. New files use the process-created owner/group and mode 0600.
 * Staging is created exclusively at 0600 before any content and narrowed to 0000 before a required identity change.
 * Non-POSIX or a Java-exposed ACL view on parent/target/stage is refused. No ACLs are copied. Absence of an ACL view
 * does not establish absence of hidden ACLs/default ACLs, labels or xattrs. Do not adopt this API where preserving
 * those, special mode bits, alternate streams, inode identity, hard-link relationships, creation times or arbitrary
 * flags is required. Replacement uses a new inode; hard-link aliases retain old content. Unknown metadata has
 * replacement/new-file semantics; not every incompatible layout can be detected.
 * <p>
 * <b>Commit and limits.</b> Complete UTF-8 contents and promised metadata are forced with {@code force(true)} and
 * closed before atomic replacement, or exclusive native POSIX hard-link creation. Unsupported rename/link operations
 * fail without copy, ordinary-move, truncate or journal fallback. The POSIX link contract, not a blanket guarantee for
 * every NIO provider, underlies exclusive creation. With a functioning OS/filesystem a process crash leaves old/absent
 * or complete-new final content. Directory forcing after commit is best effort and warns on failure; WRITTEN is not
 * absolute power-loss durability. OS crashes, remote storage, filesystem recovery and device failure are outside this
 * guarantee. This is single-file publication, not a multi-file transaction.
 * <p>
 * Same-JVM gate writes serialize and recheck bytes/kind/promised metadata before commit. There is no cross-process
 * lock or guarantee against an external edit/rename/chmod in the final check-to-publication interval; do not share the
 * file with concurrent uncoordinated writers. No startup scan, journal, replay, promotion or rollback is performed.
 * Only the exact current invocation's known-file-key regular temp may be cleaned. A crash can leave a partial/complete
 * {@code <name>.ultitools-stage-<32 lower-case hex>.tmp}, or a second hard link after creation. Later calls leave it
 * alone. Stop competing writers/server, inspect the target and named temp, and never assume a temp is authoritative
 * or promote it automatically. Delete only one recognized as no longer needed; otherwise keep it and use an independently
 * trusted backup or explicit recreate action. Retained temps may contain new data under the declared final mode: protect
 * them like the YAML file. Legacy {@link OperatorFiles#write} and internal writer defaults are unchanged.
 *
 * @since 6.4.0
 */
public final class CrashSafeYamlFiles {
    private CrashSafeYamlFiles() {
    }

    /**
     * Writes only explicitly edited keys while the existing file still holds the snapshot's bytes. No unowned byte is
     * authorized to change (see the class contract). Empty edits and semantic no-ops do not stage or change metadata.
     *
     * @param readAt snapshot from {@link OperatorFiles#read(File)} on which the edit was decided
     * @param values complete key paths to plain-data values; dots within a key are literal, null values are valid
     * @return WRITTEN, UNCHANGED, FILE_CHANGED (including a missing target), or REFUSED with a value-free warning
     * @throws IllegalArgumentException for null arguments, null/empty key paths, null keys or non-plain values, before I/O
     * @throws IOException if staging, promised metadata or publication fails before commit; no new bytes are published
     */
    public static OperatorFiles.WriteResult write(OperatorFiles.Snapshot readAt, Map<List<String>, Object> values)
            throws IOException {
        if (readAt == null || values == null) {
            throw new IllegalArgumentException("A write needs its snapshot and values");
        }
        Map<List<String>, Object> checked = new LinkedHashMap<>();
        OwnedPaths.Builder owned = OwnedPaths.builder();
        for (Map.Entry<List<String>, Object> entry : values.entrySet()) {
            List<String> path = entry.getKey();
            if (path == null || path.isEmpty() || path.contains(null)) {
                throw new IllegalArgumentException("A key path needs at least one key and no null key");
            }
            List<String> copy = Collections.unmodifiableList(new ArrayList<>(path));
            Object value = PlainData.copy(entry.getValue());
            PlainData.requirePlain(copy, value);
            checked.put(copy, value);
            owned.value(copy);
        }
        if (checked.isEmpty()) {
            return OperatorFiles.WriteResult.UNCHANGED;
        }
        return outcome(OperatorFileWriter.writeCrashSafe(readAt.getFile().toPath(), owned.build(), readAt.getFingerprint(),
                candidate -> checked.forEach(candidate::set)));
    }

    /**
     * Publishes a complete validated root map only for an explicit operator create action, never replacing any existing
     * entry. This is why whole-file ownership cannot overwrite operator content. The parent must already exist.
     *
     * @param file trusted destination on the default local POSIX filesystem
     * @param values complete plain-data root map; whole literal keys, nullable values, empty map allowed
     * @return WRITTEN, FILE_CHANGED if an entry already exists/appears, or REFUSED; never UNCHANGED
     * @throws IllegalArgumentException for null arguments/keys or non-plain data, before I/O
     * @throws IOException if staging, metadata, parent resolution or native link publication fails before commit
     */
    @SuppressWarnings("unchecked") // PlainData validates all root keys before the copied map is used.
    public static OperatorFiles.WriteResult create(File file, Map<String, Object> values) throws IOException {
        if (file == null || values == null) {
            throw new IllegalArgumentException("A creation needs its file and root map");
        }
        Map<String, Object> checked = (Map<String, Object>) PlainData.copy(values);
        PlainData.requirePlain(Collections.emptyList(), checked);
        return outcome(OperatorFileWriter.writeCrashSafe(file.toPath(), OwnedPaths.wholeFile(), OperatorFileWriter.ABSENT,
                candidate -> checked.forEach((key, value) -> candidate.set(Collections.singletonList(key), value))));
    }

    private static OperatorFiles.WriteResult outcome(OperatorFileWriter.Result result) {
        switch (result.outcome()) {
            case WRITTEN:
                return OperatorFiles.WriteResult.WRITTEN;
            case UNCHANGED:
                return OperatorFiles.WriteResult.UNCHANGED;
            case FILE_CHANGED:
                return OperatorFiles.WriteResult.FILE_CHANGED;
            default:
                return OperatorFiles.WriteResult.REFUSED;
        }
    }
}
