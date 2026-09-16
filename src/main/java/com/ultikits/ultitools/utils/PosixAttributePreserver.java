package com.ultikits.ultitools.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.function.BiConsumer;

import org.jetbrains.annotations.ApiStatus;

/**
 * Shared implementation of the atomic-replace POSIX-attribute-preservation contract used by every
 * {@code createTempFile} + atomic-{@link java.nio.file.Files#move} replace-in-place site in this
 * framework.
 * <p>
 * {@link File#createTempFile} always creates its staging file with THIS process's own default
 * owner and a restrictive default mode, so a naive replace silently strips a hardened or
 * shared-ownership file's permissions and identity the moment it is legitimately refreshed. This
 * was originally fixed only inside {@code UltiToolsPlugin#writeBytes} for language catalogues
 * (Codex rounds 8 and 9, P2, discussion_r4012703529 and discussion_r4013501574) -- a private
 * method, not javadoc-linkable from here; extracted here (round 3, Codex finding on {@code
 * ResourceHashSidecar.java:285}, thread {@code PRRT_kwDOIcF9Es6i00gb}) so {@code
 * ResourceHashSidecar#writeAll} (also private) can apply the identical contract instead of a
 * second, independent implementation of the same fix -- two copies of this exact defect shape is
 * precisely what this milestone exists to remove.
 * <p>
 * Permission bits are best-effort: a failure to read or apply them only invokes {@code
 * onPermissionCopyFailure} so the caller can warn, and the replacement still proceeds. Owner/group
 * are NOT best-effort: an unprivileged JVM process can generally {@code chown} a file's GROUP to
 * one it already belongs to, but never its USER owner to a different user at all -- so a failure
 * to match BOTH after the attempt makes this method return {@code false}. Every caller MUST treat
 * that as "abandon the whole replacement" (never move the staging file into place), the same
 * conservative choice already made for an operator-pinned read-only file, rather than silently
 * changing who owns the replaced file -- see {@code UltiToolsPlugin#writeBytes}'s own javadoc for
 * the full history of why this is the contract, not merely a suggestion.
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
