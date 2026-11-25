package com.ultikits.ultitools.interfaces.impl.data;

import com.ultikits.ultitools.text.MessageKey;
import org.jetbrains.annotations.ApiStatus;

/** Framework-owned data messages; both bundled catalogues define every key. */
@ApiStatus.Internal
public final class DataKeys {
    public static final MessageKey DATA_JSON_CORRUPTED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "data.json.corrupted");
    public static final MessageKey DATA_MYSQL_CONNECTION_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "data.mysql.connection_failed");

    private DataKeys() {
        // Static keys only.
    }
}
