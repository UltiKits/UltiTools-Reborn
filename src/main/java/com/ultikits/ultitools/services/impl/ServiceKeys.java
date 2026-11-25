package com.ultikits.ultitools.services.impl;

import com.ultikits.ultitools.text.MessageKey;
import org.jetbrains.annotations.ApiStatus;

/** Framework-owned service messages; both bundled catalogues define every key. */
@ApiStatus.Internal
public final class ServiceKeys {
    public static final MessageKey TELEPORT_FAILED_TITLE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "teleport.failed.title");
    public static final MessageKey TELEPORT_FAILED_SUBTITLE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "teleport.failed.subtitle");
    public static final MessageKey TELEPORT_SUCCESS_TITLE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "teleport.success.title");
    public static final MessageKey TELEPORT_PENDING_TITLE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "teleport.pending.title");
    public static final MessageKey TELEPORT_PENDING_SUBTITLE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "teleport.pending.subtitle");

    private ServiceKeys() {
        // Static keys only.
    }
}
