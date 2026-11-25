package com.ultikits.ultitools.handler;

import com.ultikits.ultitools.text.MessageKey;
import org.jetbrains.annotations.ApiStatus;

/** Framework-owned handler messages; both bundled catalogues define every key. */
@ApiStatus.Internal
public final class HandlerKeys {
    public static final MessageKey PANEL_SYSLOG_CONFIG_LOADED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "panel.syslog.config_loaded");
    public static final MessageKey PANEL_SYSLOG_CONFIG_LOAD_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "panel.syslog.config_load_failed");
    public static final MessageKey PANEL_SYSLOG_RECORD_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "panel.syslog.record_failed");
    public static final MessageKey PANEL_SYSLOG_PARAMETERS = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "panel.syslog.parameters");
    public static final MessageKey PANEL_SYSLOG_SUMMARY_TITLE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "panel.syslog.summary.title");
    public static final MessageKey PANEL_SYSLOG_SUMMARY_MINIMUM_LEVEL = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "panel.syslog.summary.minimum_level");
    public static final MessageKey PANEL_SYSLOG_SUMMARY_ENABLED_LEVELS = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "panel.syslog.summary.enabled_levels");
    public static final MessageKey PANEL_SYSLOG_SUMMARY_EXCLUDED_LOGGERS = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "panel.syslog.summary.excluded_loggers");
    public static final MessageKey PANEL_SYSLOG_SUMMARY_TRANSMITTER = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "panel.syslog.summary.transmitter");
    public static final MessageKey PANEL_SYSLOG_SUMMARY_CONNECTED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "panel.syslog.summary.connected");
    public static final MessageKey PANEL_SYSLOG_SUMMARY_NOT_CONNECTED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "panel.syslog.summary.not_connected");

    private HandlerKeys() {
        // Static keys only.
    }
}
