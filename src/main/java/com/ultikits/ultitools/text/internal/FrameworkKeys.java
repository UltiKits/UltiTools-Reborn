package com.ultikits.ultitools.text.internal;

import com.ultikits.ultitools.text.MessageKey;
import org.jetbrains.annotations.ApiStatus;

/** Framework message keys; literal references elsewhere are checked against the same catalogues. */
@ApiStatus.Internal
public final class FrameworkKeys {
    public static final MessageKey UI_OK = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ui.ok");
    public static final MessageKey UI_YES = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ui.yes");
    public static final MessageKey UI_NO = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ui.no");
    public static final MessageKey UI_SUBMIT = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "ui.submit");

    public static final MessageKey FALLBACK_TIME_ZONE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE,
            "text.fallback.time_zone");
    public static final MessageKey FALLBACK_CONFIGURED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE,
            "text.fallback.configured");

    private FrameworkKeys() {
        // Constants only; add keys and both bundled translations together.
    }
}
