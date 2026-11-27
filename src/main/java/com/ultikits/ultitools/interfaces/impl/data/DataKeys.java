package com.ultikits.ultitools.interfaces.impl.data;

import com.ultikits.ultitools.text.MessageKey;
import org.jetbrains.annotations.ApiStatus;

/** Framework-owned data messages; both bundled catalogues define every key. */
@ApiStatus.Internal
public final class DataKeys {
    public static final MessageKey DATA_JSON_CORRUPTED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "data.json.corrupted");
    public static final MessageKey DATA_MYSQL_CONNECTION_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "data.mysql.connection_failed");

    /** Startup warning when a negative interval disables periodic JSON flushes. */
    public static final MessageKey DATASTORE_FLUSH_RATE_NEGATIVE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE,
            "datastore.flush_rate_negative");

    private DataKeys() {
        // Static keys only.
    }
}
