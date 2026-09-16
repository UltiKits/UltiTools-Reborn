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
     *
     * @param source                  the file about to be replaced; its attributes are the ones
     *                                to preserve. If it does not exist yet (the caller's first
     *                                write -- nothing to copy attributes FROM), {@code target} is
     *                                left with the JVM's own default attributes and this method
     *                                returns {@code true} without invoking either callback.
     * @param target                  the newly created staging file about to be moved into
     *                                {@code source}'s place
     * @param onPermissionCopyFailure invoked (with no arguments) if the permission bits could not
     *                                be read or applied; the replacement still proceeds afterward
     * @param onOwnershipCopyFailure  invoked with {@code (ownerName, groupName)} if {@code
     *                                target}'s owner/group could not be made to match {@code
     *                                source}'s
     * @return {@code true} if the replacement may proceed; {@code false} if it must be abandoned
     *         because ownership could not be replicated
     */
    public static boolean copyIfSupported(File source, File target,
            Runnable onPermissionCopyFailure,
            BiConsumer<String, String> onOwnershipCopyFailure) {
        if (!source.exists()) {
            return true;
        }
        try {
            PosixFileAttributeView sourceView =
                    Files.getFileAttributeView(source.toPath(), PosixFileAttributeView.class);
            if (sourceView == null) {
                return true;
            }
            PosixFileAttributes sourceAttributes = sourceView.readAttributes();
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
        } catch (IOException | UnsupportedOperationException e) {
            onPermissionCopyFailure.run();
            return true;
        }
    }
}
