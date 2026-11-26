package com.ultikits.ultitools;

import com.ultikits.ultitools.text.MessageKey;
import org.jetbrains.annotations.ApiStatus;

/** Framework-owned ultitools messages; both bundled catalogues define every key. */
@ApiStatus.Internal
public final class UltiToolsKeys {
    public static final MessageKey STARTUP_DATA_STORE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "startup.data_store");
    public static final MessageKey STARTUP_ENABLED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "startup.enabled");
    public static final MessageKey STARTUP_SERVER_UUID = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "startup.server_uuid");
    public static final MessageKey STARTUP_SERVER_UUID_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "startup.server_uuid_failed");
    public static final MessageKey RELOAD_FRAMEWORK_OVERRIDES_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "reload.framework_overrides_failed");

    private UltiToolsKeys() {
        // Static keys only.
    }
}
