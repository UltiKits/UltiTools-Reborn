package com.ultikits.ultitools.utils;

import com.ultikits.ultitools.text.MessageKey;
import org.jetbrains.annotations.ApiStatus;

/** Framework-owned utils messages; both bundled catalogues define every key. */
@ApiStatus.Internal
public final class UtilsKeys {
    public static final MessageKey CLOUD_LEGACY_TOKEN_REMOVED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "cloud.legacy_token_removed");
    public static final MessageKey CLOUD_SESSION_SERVER_ID_MISMATCH = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "cloud.session.server_id_mismatch");
    public static final MessageKey INSTALL_REASON_LICENCE_REQUIRED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "install.reason.licence_required");
    public static final MessageKey INSTALL_REASON_INTEGRITY_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "install.reason.integrity_failed");

    private UtilsKeys() {
        // Static keys only.
    }
}
