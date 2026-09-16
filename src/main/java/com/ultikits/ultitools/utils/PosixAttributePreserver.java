package com.ultikits.ultitools.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.function.BiConsumer;

import org.jetbrains.annotations.ApiStatus;

/**
 * Shared implementation of the "replace a file in place, preserving what the operator set up"
 * operation used by every {@code createTempFile} + atomic-{@link java.nio.file.Files#move}
 * replace-in-place site in this framework.
 * <p>
 * Round following {@code PosixAttributePreserver.java:104} (thread {@code
 * PRRT_kwDOIcF9Es6i2I49}, symlink finding on {@code ResourceHashSidecar.java:320}, P2): this class
 * originally covered only POSIX attribute preservation ({@link #copyIfSupported}), extracted from
 * {@code UltiToolsPlugin#writeBytes} in round 3 so {@code ResourceHashSidecar#writeAll} could
 * reuse it. That left the OTHER properties of the same operation -- the operator-pinned-read-only
 * refusal and the symlink refusal -- copied only into {@code writeBytes}, so a reviewer kept
 * finding sibling gaps in the sidecar's path one property at a time across four rounds: attribute
 * preservation (round 3), an unreadable-attributes edge case (round 6), and finally symlink
 * handling. {@link #replaceInPlace} now owns the WHOLE operation -- both refusal checks, the
 * attribute-preservation step, the atomic write itself, and cleanup -- so a third replace-in-place
 * site gets every property for free by calling one method, instead of a reviewer (or a human)
 * having to notice each property was missing one at a time. {@link #copyIfSupported} remains
 * public and unchanged for any caller that only needs the attribute-copy step in isolation; {@link
 * #replaceInPlace} calls it internally as one part of the larger operation.
 * <p>
 * {@link File#createTempFile} always creates its staging file with THIS process's own default
 * owner and a restrictive default mode, so a naive replace silently strips a hardened or
 * shared-ownership file's permissions and identity the moment it is legitimately refreshed. This
 * was originally fixed only inside {@code UltiToolsPlugin#writeBytes} for language catalogues
 * (Codex rounds 8 and 9, P2, discussion_r4012703529 and discussion_r4013501574).
 * <p>
 * Permission bits are best-effort: a failure to read or apply them only invokes {@code
 * onPermissionCopyFailure} so the caller can warn, and the replacement still proceeds. Owner/group
 * are NOT best-effort: an unprivileged JVM process can generally {@code chown} a file's GROUP to
 * one it already belongs to, but never its USER owner to a different user at all -- so a failure
 * to match BOTH after the attempt makes {@link #copyIfSupported} return {@code false}. Every
 * caller MUST treat that as "abandon the whole replacement" (never move the staging file into
 * place), the same conservative choice already made for an operator-pinned read-only file or a
 * symlinked target -- see {@link #replaceInPlace}'s own javadoc for the full contract.
 * <p>
 * {@code public} only so {@code UltiToolsPlugin} (a different package) and {@link
 * ResourceHashSidecar} can share it -- internal framework plumbing, not a documented capability
 * (same rationale as {@link ResourceHashSidecar}'s own class javadoc): it appears nowhere in
 * {@code FEATURES.md} as its own row, and this milestone's own D-09 decision rejects shipping new
 * public surface in 6.3.0 for exactly this reason.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class PosixAttributePreserver {

    private PosixAttributePreserver() {
    }

    /**
     * Callbacks a {@link #replaceInPlace} caller supplies so each replace-in-place site can log
     * accurate, resource-specific wording for exactly how the operation was declined or degraded
     * -- this shared operation does not know or care whether {@code target} is a language
     * catalogue or a provenance sidecar, only the caller does.
     */
    public interface ReplaceInPlaceListener {

        /**
         * {@code target} exists and none of its POSIX write-permission bits (owner, group, or
         * other) are set for the current process -- treated as a deliberate operator hardening
         * signal, exactly like a symlinked target. The replacement is refused; {@code target} is
         * left completely untouched.
         */
        void onOperatorPinnedReadOnly();

        /**
         * {@code target} is a symbolic link. {@code writeBytes}'s pre-existing contract (see
         * {@link #replaceInPlace}'s own javadoc) is REFUSE, not write-through-to-target: the
         * replacement is declined entirely, exactly like an operator-pinned read-only file,
         * rather than either replacing the link itself (which would silently break an
         * operator-managed shared layout) or resolving and writing through to the link's target
         * (which this shared operation does not attempt, for the same relative-vs-absolute-link
         * and shared-target reasons {@code writeBytes}'s own javadoc already gives).
         */
        void onSymbolicLink();

        /**
         * A POSIX view exists for {@code target} but reading its current attributes failed --
         * see {@link #copyIfSupported}'s own javadoc for why this is distinct from "no POSIX view
         * at all" and must also abandon the replacement.
         */
        void onSourceAttributesUnreadable();

        /**
         * {@code target}'s permission bits were read successfully but could not be applied to the
         * staging file; best-effort only, the replacement still proceeds.
         */
        void onPermissionCopyFailure();

        /**
         * {@code target}'s owner/group could not be replicated onto the staging file; the
         * replacement is abandoned.
         *
         * @param ownerName the owner name {@code target} is owned by
         * @param groupName the group name {@code target} is owned by
         */
        void onOwnershipCopyFailure(String ownerName, String groupName);

        /**
         * The atomic write or move itself failed (e.g. the filesystem filled up mid-write, or a
         * transient I/O error during the rename). {@code target} is guaranteed untouched -- the
         * staging file is written and moved only after every guard above has already passed, and
         * a failure here never truncates or partially overwrites {@code target} itself.
         *
         * @param cause the underlying {@link IOException}
         */
        void onWriteFailure(IOException cause);
    }

    /**
     * Replaces {@code target}'s content with {@code content}, atomically, preserving everything
     * about {@code target} an operator may have deliberately set up -- its POSIX
     * permissions/owner/group (see {@link #copyIfSupported}), whether it has been pinned
     * read-only, and whether it is a symbolic link.
     * <p>
     * This is {@code UltiToolsPlugin#writeBytes}'s own algorithm, generalized so both the
     * language-file path and {@code ResourceHashSidecar#writeAll} run the IDENTICAL operation
     * instead of two independent copies of it: check operator-pinned-read-only (refuse if so);
     * check symbolic link (refuse if so -- see {@link ReplaceInPlaceListener#onSymbolicLink()}
     * for the exact semantics this refusal implements); create a temp file in {@code target}'s
     * own parent directory (creating that directory first if it does not exist yet, needed for a
     * sidecar's very first write); copy POSIX attributes onto the staging file via {@link
     * #copyIfSupported} (abandon on failure); write {@code content} to the staging file; move it
     * into {@code target}'s place with {@link StandardCopyOption#REPLACE_EXISTING} and {@link
     * StandardCopyOption#ATOMIC_MOVE}. The staging file is always cleaned up in a {@code finally}
     * block, whether the move succeeded (a no-op, since a successful move already renamed it away)
     * or any guard above aborted first.
     *
     * @param target   the file to replace; if it does not exist yet, this is treated as the
     *                 caller's first write and the read-only/symlink checks both pass trivially
     * @param content  the exact bytes {@code target} should contain after a successful call
     * @param listener receives exactly one callback describing why the replacement was declined
     *                 or degraded, or none at all on an unqualified success
     * @return {@code true} if {@code target} now contains {@code content}; {@code false} if the
     *         replacement was refused or failed for any reason -- {@code target} is guaranteed
     *         unchanged in that case
     */
    public static boolean replaceInPlace(File target, byte[] content, ReplaceInPlaceListener listener) {
        if (isOperatorPinnedReadOnly(target)) {
            listener.onOperatorPinnedReadOnly();
            return false;
        }
        if (Files.isSymbolicLink(target.toPath())) {
            listener.onSymbolicLink();
            return false;
        }
        File parentDir = target.getParentFile();
        File tempFile = null;
        try {
            if (parentDir != null && !parentDir.isDirectory()) {
                Files.createDirectories(parentDir.toPath());
            }
            tempFile = File.createTempFile(target.getName(), ".tmp", parentDir);
            if (!copyIfSupported(target, tempFile,
                    listener::onSourceAttributesUnreadable,
                    listener::onPermissionCopyFailure,
                    listener::onOwnershipCopyFailure)) {
                return false;
            }
            Files.write(tempFile.toPath(), content);
            Files.move(tempFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            listener.onWriteFailure(e);
            return false;
        } finally {
            if (tempFile != null) {
                // A successful move already renamed the temp file away from tempFile's own path,
                // so this is a no-op on the success path and only cleans up a leftover staging
                // file on any failure branch above.
                // noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
        }
    }

    /**
     * Returns whether {@code file} should be treated as pinned read-only by its operator, and
     * therefore never refreshed by {@link #replaceInPlace} -- {@code false} for a file that does
     * not exist yet (nothing to pin).
     * <p>
     * {@link #replaceInPlace}'s atomic move replaces a DIRECTORY ENTRY, which on POSIX only ever
     * consults the containing directory's write permission, never the target file's own -- so an
     * untouched file the operator made read-only (a deliberate hardening signal) would otherwise
     * be silently overwritten anyway if checked with {@link Files#isWritable} alone.
     * <p>
     * {@link Files#isWritable} alone reflects only this PROCESS's effective ability to write,
     * which is unconditionally {@code true} under a privileged JVM (root, or {@code
     * CAP_DAC_OVERRIDE}) regardless of the file's own mode bits -- a server running privileged
     * would otherwise silently ignore an operator's {@code chmod 0444} pin. This method therefore
     * also inspects the raw POSIX write bits directly as a fallback signal, independent of what
     * this particular process happens to be privileged to do: if none of owner/group/other
     * carries write permission, the operator's intent is unambiguous. A non-POSIX filesystem, or
     * any failure reading the permissions, falls back to the (already passed) process-relative
     * answer -- this fallback only ever STRENGTHENS the read-only determination, never weakens it.
     * <p>
     * Moved here verbatim from {@code UltiToolsPlugin#isOperatorPinnedReadOnly} (Codex rounds 8
     * and 10) as part of consolidating the whole replace-in-place operation into one place -- this
     * method never used any {@code UltiToolsPlugin} instance state, so the move changes nothing
     * about its behaviour.
     *
     * @param file the file about to be replaced
     * @return whether the replacement must be refused because the file is operator-pinned
     *         read-only
     */
    private static boolean isOperatorPinnedReadOnly(File file) {
        if (!file.exists()) {
            return false;
        }
        if (!Files.isWritable(file.toPath())) {
            return true;
        }
        try {
            PosixFileAttributeView view = Files.getFileAttributeView(file.toPath(), PosixFileAttributeView.class);
            if (view == null) {
                return false;
            }
            Set<PosixFilePermission> permissions = view.readAttributes().permissions();
            return !permissions.contains(PosixFilePermission.OWNER_WRITE)
                    && !permissions.contains(PosixFilePermission.GROUP_WRITE)
                    && !permissions.contains(PosixFilePermission.OTHERS_WRITE);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Copies {@code source}'s POSIX permissions and owner/group onto {@code target}, if the
     * filesystem exposes a {@link PosixFileAttributeView} for it.
     * <p>
     * Round on {@code PosixAttributePreserver.java:104} (thread {@code PRRT_kwDOIcF9Es6i12fM},
     * P2): "no POSIX view for this filesystem" and "a POSIX view exists but reading it failed"
     * are deliberately kept as two separate failure modes with two separate outcomes, not folded
     * into one catch as an earlier version of this method did. The first case (the view lookup
     * yields {@code null}, or {@link Files#getFileAttributeView} itself throws {@link
     * UnsupportedOperationException}) means there is genuinely nothing to preserve -- proceeding
     * without copying is correct (the Windows / exotic-filesystem case). The second case ({@code
     * sourceView.readAttributes()} throws {@link IOException}, e.g. a transient NFS error) means
     * the opposite: a POSIX identity DOES exist to preserve, and this call could not learn what it
     * was. Treating that the same as "nothing to preserve" would silently let {@code target} keep
     * {@link File#createTempFile}'s process-default identity -- reopening, on this error path,
     * exactly the hole the ownership-preservation fix (Codex rounds 8/9) closed on the happy path.
     * This method therefore returns {@code false} for an unreadable source view, the same outcome
     * already returned when ownership cannot be replicated, so the caller's strict "abandon the
     * whole replacement" half runs instead of silently proceeding.
     *
     * @param source                       the file about to be replaced; its attributes are the
     *                                     ones to preserve. If it does not exist yet (the
     *                                     caller's first write -- nothing to copy attributes
     *                                     FROM), {@code target} is left with the JVM's own
     *                                     default attributes and this method returns {@code true}
     *                                     without invoking any callback.
     * @param target                       the newly created staging file about to be moved into
     *                                     {@code source}'s place
     * @param onSourceAttributesUnreadable invoked (with no arguments) if a POSIX view exists for
     *                                     {@code source} but reading its attributes failed -- the
     *                                     replacement MUST be abandoned; see the class/method
     *                                     javadoc above for why this is distinct from "no POSIX
     *                                     view at all"
     * @param onPermissionCopyFailure      invoked (with no arguments) if {@code source}'s
     *                                     permission bits were read successfully but could not be
     *                                     applied to {@code target}; the replacement still
     *                                     proceeds afterward (best-effort)
     * @param onOwnershipCopyFailure       invoked with {@code (ownerName, groupName)} if {@code
     *                                     target}'s owner/group could not be made to match
     *                                     {@code source}'s
     * @return {@code true} if the replacement may proceed; {@code false} if it must be abandoned
     *         because {@code source}'s attributes could not be read, or its ownership could not
     *         be replicated
     */
    public static boolean copyIfSupported(File source, File target,
            Runnable onSourceAttributesUnreadable,
            Runnable onPermissionCopyFailure,
            BiConsumer<String, String> onOwnershipCopyFailure) {
        if (!source.exists()) {
            return true;
        }
        PosixFileAttributeView sourceView;
        try {
            sourceView = Files.getFileAttributeView(source.toPath(), PosixFileAttributeView.class);
        } catch (UnsupportedOperationException e) {
            // Genuinely no POSIX view for this filesystem -- nothing to preserve, proceeding is
            // correct.
            return true;
        }
        if (sourceView == null) {
            return true;
        }
        PosixFileAttributes sourceAttributes;
        try {
            sourceAttributes = sourceView.readAttributes();
        } catch (IOException e) {
            // The view EXISTS -- this filesystem does support POSIX attributes -- but reading it
            // failed. Unlike the "no view" case above, there IS an identity to preserve here and
            // this call does not know what it is; abandon the replacement rather than silently
            // let target keep createTempFile's process-default identity.
            onSourceAttributesUnreadable.run();
            return false;
        }
        Set<PosixFilePermission> permissions = sourceAttributes.permissions();
        try {
            Files.setPosixFilePermissions(target.toPath(), permissions);
        } catch (IOException e) {
            onPermissionCopyFailure.run();
        }
        PosixFileAttributeView targetView =
                Files.getFileAttributeView(target.toPath(), PosixFileAttributeView.class);
        try {
            targetView.setGroup(sourceAttributes.group());
            targetView.setOwner(sourceAttributes.owner());
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            onOwnershipCopyFailure.accept(sourceAttributes.owner().getName(), sourceAttributes.group().getName());
            return false;
        }
    }
}
