package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.text.MessageKey;
import org.jetbrains.annotations.ApiStatus;

/** Framework-owned manager messages; both bundled catalogues define every key. */
@ApiStatus.Internal
public final class ManagerKeys {
    public static final MessageKey MODULE_DUPLICATE_MAIN_CLASS = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "module.duplicate_main_class");
    public static final MessageKey MODULE_MISSING_REQUIRED_PLUGIN = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "module.missing_required_plugin");
    public static final MessageKey MODULE_API_VERSION_UNREADABLE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "module.api_version.unreadable");
    public static final MessageKey MODULE_API_VERSION_INVALID = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "module.api_version.invalid");
    public static final MessageKey MODULE_API_VERSION_TOO_LOW = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "module.api_version.too_low");
    public static final MessageKey MODULE_BOOTSTRAP_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "module.bootstrap.failed");
    public static final MessageKey RELOAD_PARTIAL = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "reload.partial");
    public static final MessageKey RELOAD_SUMMARY_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "reload.summary.failed");
    public static final MessageKey RELOAD_SUMMARY_MIXED_PARTIAL = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "reload.summary.mixed_partial");
    public static final MessageKey RELOAD_SUMMARY_PARTIAL = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "reload.summary.partial");
    public static final MessageKey RELOAD_SUMMARY_ALL = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "reload.summary.all");
    public static final MessageKey CONFIG_BINDING_TIMER_RULE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "config.binding.timer_rule");
    public static final MessageKey CONFIG_BINDING_COOLDOWN_RULE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "config.binding.cooldown_rule");
    public static final MessageKey CONFIG_BINDING_COOLDOWN_INVALID = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "config.binding.cooldown_invalid");
    public static final MessageKey CONFIG_BINDING_RELOAD_INCOMPLETE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "config.binding.reload_incomplete");
    public static final MessageKey CONFIG_BINDING_SCHEDULED_KEPT = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "config.binding.scheduled_kept");
    public static final MessageKey CONFIG_BINDING_SCHEDULED_NOT_VALIDATED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "config.binding.scheduled_not_validated");
    public static final MessageKey CONFIG_BINDING_COOLDOWN_KEPT = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "config.binding.cooldown_kept");
    public static final MessageKey CONFIG_BINDING_SCHEDULED_INVALID_WARNING = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "config.binding.scheduled_invalid_warning");
    public static final MessageKey CONFIG_BINDING_SCHEDULED_NOT_VALIDATED_WARNING = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "config.binding.scheduled_not_validated_warning");
    public static final MessageKey CONFIG_BINDING_COOLDOWN_NOT_UPDATED_WARNING = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "config.binding.cooldown_not_updated_warning");
    public static final MessageKey CONFIG_BINDING_STEP_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "config.binding.step_failed");
    public static final MessageKey CONFIG_BINDING_OFF_THREAD = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "config.binding.off_thread");
    public static final MessageKey DATASTORE_REASON_MYSQL_DISABLED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "datastore.reason.mysql_disabled");
    public static final MessageKey DATASTORE_REASON_MYSQL_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "datastore.reason.mysql_failed");
    public static final MessageKey DATASTORE_REASON_NO_PROVIDER = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "datastore.reason.no_provider");
    public static final MessageKey DATASTORE_DOWNGRADED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "datastore.downgraded");
    public static final MessageKey DATASTORE_PLAYER_DATA_STORE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "datastore.player_data_store");
    public static final MessageKey UPDATE_CHECKING = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "update.checking");
    public static final MessageKey UPDATE_FRAMEWORK_AVAILABLE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "update.framework_available");
    public static final MessageKey UPDATE_DOWNLOAD = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "update.download");
    public static final MessageKey UPDATE_MODULES_AVAILABLE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "update.modules_available");
    public static final MessageKey UPDATE_INCOMPLETE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "update.incomplete");
    public static final MessageKey UPDATE_UP_TO_DATE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "update.up_to_date");
    public static final MessageKey REMOTE_COMMAND_BLOCKED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "remote_command.blocked");
    public static final MessageKey REMOTE_COMMAND_ECHO = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "remote_command.echo");
    public static final MessageKey REMOTE_COMMAND_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "remote_command.failed");
    public static final MessageKey PANEL_LOG_STREAM_LISTENER_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "panel.log_stream.listener_failed");
    public static final MessageKey MONITOR_CPU_UNAVAILABLE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "monitor.cpu_unavailable");
    public static final MessageKey MONITOR_OFF_MAIN_THREAD = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "monitor.off_main_thread");
    public static final MessageKey MONITOR_SAMPLE_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "monitor.sample_failed");
    public static final MessageKey MONITOR_TPS_UPDATE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "monitor.tps_update");
    public static final MessageKey SERVER_PROPERTIES_BATCH_INCOMPLETE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "server_properties.batch.incomplete");
    public static final MessageKey SERVER_PROPERTIES_BATCH_REJECTED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "server_properties.batch.rejected");
    public static final MessageKey SERVER_PROPERTIES_BATCH_WRITE_FAILED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "server_properties.batch.write_failed");
    public static final MessageKey SERVER_PROPERTIES_BATCH_MALFORMED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "server_properties.batch.malformed");
    public static final MessageKey SERVER_PROPERTIES_BATCH_NOT_PRESENT = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "server_properties.batch.not_present");
    public static final MessageKey PANEL_LOG_ENABLED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "panel.log.enabled");
    public static final MessageKey PANEL_LOG_DISABLED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "panel.log.disabled");
    public static final MessageKey PANEL_LOG_CLOSED = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "panel.log.closed");
    public static final MessageKey UPDATE_JOIN_NOTICE = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, "update.join_notice");

    private ManagerKeys() {
        // Static keys only.
    }
}
